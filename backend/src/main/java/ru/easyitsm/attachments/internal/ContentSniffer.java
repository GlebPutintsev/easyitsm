package ru.easyitsm.attachments.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Определяет тип файла по содержимому (SEC-25): по сигнатуре первых байт, а не по имени и не по заголовку
 * клиента. Исполняемые файлы распознаются при любом расширении.
 */
final class ContentSniffer {

    /** Результат: MIME-тип и признаки для политики. */
    record Kind(String mime, boolean executable, boolean zipContainer, boolean previewable) {
    }

    /** Картинки, которые браузер показывает без исполнения кода. SVG сюда не входит: в нём бывают скрипты. */
    static final Set<String> PREVIEWABLE = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private ContentSniffer() {
    }

    static Kind sniff(byte[] head, String fileName) {
        String ext = extension(fileName);
        if (starts(head, 'M', 'Z')) {
            return exe("application/x-msdownload");
        }
        if (starts(head, 0x7f, 'E', 'L', 'F')) {
            return exe("application/x-executable");
        }
        if (starts(head, 0xfe, 0xed, 0xfa, 0xce) || starts(head, 0xfe, 0xed, 0xfa, 0xcf)
                || starts(head, 0xcf, 0xfa, 0xed, 0xfe) || starts(head, 0xce, 0xfa, 0xed, 0xfe)
                || (starts(head, 0xca, 0xfe, 0xba, 0xbe) && !ext.equals("class"))) {
            return exe("application/x-mach-binary");
        }
        if (starts(head, '#', '!')) {
            return exe("text/x-shellscript");
        }
        if (starts(head, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) {
            return image("image/png");
        }
        if (starts(head, 0xff, 0xd8, 0xff)) {
            return image("image/jpeg");
        }
        if (starts(head, 'G', 'I', 'F', '8')) {
            return image("image/gif");
        }
        if (head.length >= 12 && starts(head, 'R', 'I', 'F', 'F') && head[8] == 'W' && head[9] == 'E'
                && head[10] == 'B' && head[11] == 'P') {
            return image("image/webp");
        }
        if (starts(head, '%', 'P', 'D', 'F', '-')) {
            return plain("application/pdf");
        }
        if (starts(head, 'P', 'K', 0x03, 0x04) || starts(head, 'P', 'K', 0x05, 0x06)) {
            String mime = switch (ext) {
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "odt" -> "application/vnd.oasis.opendocument.text";
                case "ods" -> "application/vnd.oasis.opendocument.spreadsheet";
                default -> "application/zip";
            };
            return new Kind(mime, false, true, false);
        }
        if (starts(head, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) {
            return plain("application/x-ole-storage");
        }
        if (starts(head, 0x1f, 0x8b)) {
            return plain("application/gzip");
        }
        if (starts(head, '7', 'z', 0xbc, 0xaf, 0x27, 0x1c)) {
            return plain("application/x-7z-compressed");
        }
        if (starts(head, 'R', 'a', 'r', '!')) {
            return plain("application/vnd.rar");
        }
        String text = new String(head, 0, Math.min(head.length, 512), StandardCharsets.UTF_8).stripLeading()
                .toLowerCase(Locale.ROOT);
        if (text.startsWith("<svg") || (text.startsWith("<?xml") && text.contains("<svg"))) {
            return plain("image/svg+xml");
        }
        if (text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<script")) {
            return plain("text/html");
        }
        if (isText(head)) {
            return plain("text/plain");
        }
        return plain("application/octet-stream");
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Kind exe(String mime) {
        return new Kind(mime, true, false, false);
    }

    private static Kind image(String mime) {
        return new Kind(mime, false, false, true);
    }

    private static Kind plain(String mime) {
        return new Kind(mime, false, false, false);
    }

    private static boolean isText(byte[] head) {
        int n = Math.min(head.length, 4096);
        for (int i = 0; i < n; i++) {
            int b = head[i] & 0xff;
            if (b == 0 || (b < 0x09) || (b > 0x0d && b < 0x20 && b != 0x1b)) {
                return false;
            }
        }
        return n > 0;
    }

    private static boolean starts(byte[] head, int... sig) {
        if (head.length < sig.length) {
            return false;
        }
        byte[] expected = new byte[sig.length];
        for (int i = 0; i < sig.length; i++) {
            expected[i] = (byte) sig[i];
        }
        return Arrays.equals(Arrays.copyOf(head, sig.length), expected);
    }
}
