package ru.easyitsm.attachments.internal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Проверка ZIP-архивов и документов Office на архивные бомбы и вложенные исполняемые файлы (SEC-27, T-07):
 * глубина вложенности, число файлов, распакованный размер, степень сжатия, шифрование. Читает потоком,
 * ничего не распаковывая на диск.
 */
final class ArchiveInspector {

    /** Нарушение: причина для журнала и карантина. */
    record Violation(String reason) {
    }

    private final AttachmentProperties.Archives limits;
    private final Set<String> blockedExtensions;
    private int entries;
    private long unpacked;

    ArchiveInspector(AttachmentProperties.Archives limits, Set<String> blockedExtensions) {
        this.limits = limits;
        this.blockedExtensions = blockedExtensions;
    }

    /** @return нарушение или null, если архив в пределах */
    Violation inspect(Path file) throws IOException {
        long compressed = Files.size(file);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            Violation v = walk(in, 1);
            if (v == null && compressed > 0 && unpacked / compressed > limits.maxRatio()) {
                return new Violation("archive_ratio");
            }
            return v;
        }
    }

    private Violation walk(InputStream in, int depth) throws IOException {
        if (depth > limits.maxDepth()) {
            return new Violation("archive_depth");
        }
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry e;
        try {
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > limits.maxEntries()) {
                    return new Violation("archive_entries");
                }
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                String ext = ContentSniffer.extension(name.substring(name.lastIndexOf('/') + 1));
                if (blockedExtensions.contains(ext)) {
                    return new Violation("archive_blocked_file:" + ext);
                }
                byte[] head = new byte[16];
                int headLen = zip.readNBytes(head, 0, head.length);
                byte[] h = java.util.Arrays.copyOf(head, headLen);
                ContentSniffer.Kind kind = ContentSniffer.sniff(h, name);
                if (kind.executable()) {
                    return new Violation("archive_executable");
                }
                long size = headLen;
                if (kind.zipContainer() && ext.equals("zip")) {
                    unpacked += size;
                    Violation nested = walk(new PrefixedInputStream(h, zip), depth + 1);
                    if (nested != null) {
                        return nested;
                    }
                    continue;
                }
                long compressed = e.getCompressedSize();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = zip.read(buf)) > 0) {
                    size += n;
                    unpacked += n;
                    if (unpacked > limits.maxUnpacked().toBytes()) {
                        return new Violation("archive_size");
                    }
                    if (compressed > 0 && size / compressed > limits.maxRatio() && size > 1024 * 1024) {
                        return new Violation("archive_ratio");
                    }
                }
                unpacked += headLen;
            }
        } catch (ZipException ex) {
            // Зашифрованный элемент ZipInputStream не читает и сообщает об этом исключением.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("encrypt")) {
                return limits.allowEncrypted() ? null : new Violation("archive_encrypted");
            }
            return depth == 1 ? new Violation("archive_corrupt") : null;
        }
        return null;
    }

    /** Поток, перед которым уже прочитанные байты заголовка. Не закрывает внешний ZIP. */
    private static final class PrefixedInputStream extends InputStream {
        private final byte[] prefix;
        private final InputStream rest;
        private int pos;

        PrefixedInputStream(byte[] prefix, InputStream rest) {
            this.prefix = prefix;
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            return pos < prefix.length ? prefix[pos++] & 0xff : rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos < prefix.length) {
                int n = Math.min(len, prefix.length - pos);
                System.arraycopy(prefix, pos, b, off, n);
                pos += n;
                return n;
            }
            return rest.read(b, off, len);
        }

        @Override
        public void close() {
            // внешний ZipInputStream закрывает владелец
        }
    }
}
