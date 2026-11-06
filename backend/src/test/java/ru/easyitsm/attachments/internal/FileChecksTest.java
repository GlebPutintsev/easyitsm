package ru.easyitsm.attachments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import ru.easyitsm.platform.web.ApiException;

/** Проверки содержимого без хранилища: сигнатуры, архивы, ответы clamd, имена файлов. */
class FileChecksTest {

    @TempDir
    Path dir;

    static final AttachmentProperties.Archives LIMITS = new AttachmentProperties.Archives(3, 100, DataSize.ofMegabytes(50),
            100, false);
    static final Set<String> BLOCKED = Set.of("exe", "bat", "js");

    @Test
    void typeIsDetectedByContentNotByName() {
        assertThat(ContentSniffer.sniff(bytes(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0), "photo.png").mime())
                .isEqualTo("image/png");
        ContentSniffer.Kind fakeImage = ContentSniffer.sniff("MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1),
                "photo.png");
        assertThat(fakeImage.executable()).isTrue();
        assertThat(fakeImage.previewable()).isFalse();
        assertThat(ContentSniffer.sniff(bytes(0x7f, 'E', 'L', 'F', 2), "readme.txt").executable()).isTrue();
        assertThat(ContentSniffer.sniff("#!/bin/sh\nrm -rf /".getBytes(), "notes.txt").executable()).isTrue();
        assertThat(ContentSniffer.sniff("%PDF-1.7".getBytes(), "doc.pdf").mime()).isEqualTo("application/pdf");
        ContentSniffer.Kind svg = ContentSniffer.sniff("<svg onload=alert(1)>".getBytes(), "logo.png");
        assertThat(svg.mime()).isEqualTo("image/svg+xml");
        assertThat(svg.previewable()).isFalse();
        assertThat(ContentSniffer.sniff("Привет, мир".getBytes(StandardCharsets.UTF_8), "a.log").mime())
                .isEqualTo("text/plain");
        assertThat(ContentSniffer.sniff(bytes('P', 'K', 3, 4), "report.docx").zipContainer()).isTrue();
    }

    @Test
    void zipBombIsBlockedByRatio() throws IOException {
        Path zip = zip(dir.resolve("bomb.zip"), out -> {
            out.putNextEntry(new ZipEntry("zeros.bin"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 40; i++) {
                out.write(zeros);
            }
            out.closeEntry();
        });
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(zip).reason()).isEqualTo("archive_ratio");
    }

    @Test
    void nestedArchivesDeeperThanLimitAreBlocked() throws IOException {
        byte[] inner = "текст".getBytes(StandardCharsets.UTF_8);
        for (int depth = 0; depth < 4; depth++) {
            inner = zipBytes("level" + depth + ".zip", inner);
        }
        Path file = dir.resolve("nested.zip");
        Files.write(file, inner);
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(file).reason()).isEqualTo("archive_depth");

        byte[] shallow = zipBytes("a.zip", zipBytes("b.txt", "ok".getBytes()));
        Path ok = dir.resolve("ok.zip");
        Files.write(ok, shallow);
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(ok)).isNull();
    }

    @Test
    void executablesInsideArchivesAreBlocked() throws IOException {
        Path byName = zip(dir.resolve("byname.zip"), out -> {
            out.putNextEntry(new ZipEntry("docs/setup.exe"));
            out.write("hello".getBytes());
            out.closeEntry();
        });
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(byName).reason()).isEqualTo("archive_blocked_file:exe");

        Path byContent = zip(dir.resolve("bycontent.zip"), out -> {
            out.putNextEntry(new ZipEntry("photo.jpg"));
            out.write("MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1));
            out.closeEntry();
        });
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(byContent).reason()).isEqualTo("archive_executable");
    }

    @Test
    void tooManyEntries() throws IOException {
        Path many = zip(dir.resolve("many.zip"), out -> {
            for (int i = 0; i < 150; i++) {
                out.putNextEntry(new ZipEntry("f" + i + ".txt"));
                out.write(("файл " + i).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        });
        assertThat(new ArchiveInspector(LIMITS, BLOCKED).inspect(many).reason()).isEqualTo("archive_entries");
    }

    @Test
    void clamdReplies() throws Exception {
        assertThat(ClamdClient.parse("stream: OK").infected()).isFalse();
        ClamdClient.Result r = ClamdClient.parse("stream: Win.Test.EICAR_HDB-1 FOUND");
        assertThat(r.infected()).isTrue();
        assertThat(r.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
        assertThatThrownBy(() -> ClamdClient.parse("INSTREAM size limit exceeded. ERROR"))
                .isInstanceOf(ClamdClient.ScanFailed.class);
    }

    @Test
    void fileNamesAreCleaned() {
        assertThat(AttachmentService.cleanName("C:\\Users\\me\\отчёт.pdf")).isEqualTo("отчёт.pdf");
        assertThat(AttachmentService.cleanName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentService.cleanName("invoice\u202Efdp.exe")).isEqualTo("invoicefdp.exe");
        assertThat(AttachmentService.cleanName("..hidden")).isEqualTo("hidden");
        assertThatThrownBy(() -> AttachmentService.cleanName("   ")).isInstanceOf(ApiException.class);
        assertThat(AttachmentService.safeMime("text/html; charset=utf-8")).isEqualTo("application/octet-stream");
        assertThat(AttachmentService.safeMime("image/PNG")).isEqualTo("image/png");
        assertThat(ObjectStorage.asciiName("отчёт \"Q1\".pdf")).isEqualTo("_____ _Q1_.pdf");
    }

    interface ZipBody {
        void write(ZipOutputStream out) throws IOException;
    }

    private static Path zip(Path file, ZipBody body) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            body.write(out);
        }
        return file;
    }

    private static byte[] zipBytes(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bos)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content);
            out.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }
}
