package ru.easyitsm.mail.internal;

import java.util.List;
import java.util.Map;

/**
 * Разобранное входящее письмо.
 *
 * @param headers      все заголовки в исходном порядке (имя в нижнем регистре → значения)
 * @param text         текст письма: text/plain или очищенный HTML, без цитаты предыдущей переписки
 * @param attachments  имена вложений (сами файлы — тред «Поиск и вложения»)
 * @param report       письмо-отчёт о доставке (multipart/report, DSN)
 */
record ParsedMail(
        String messageId,
        String inReplyTo,
        String references,
        String fromAddress,
        String fromName,
        String returnPath,
        String subject,
        String text,
        Map<String, List<String>> headers,
        List<String> attachments,
        boolean report,
        byte[] raw) {

    String header(String name) {
        List<String> v = headers.get(name.toLowerCase());
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    List<String> headerAll(String name) {
        List<String> v = headers.get(name.toLowerCase());
        return v == null ? List.of() : v;
    }

    String fromDomain() {
        if (fromAddress == null) {
            return null;
        }
        int at = fromAddress.lastIndexOf('@');
        return at < 0 ? null : fromAddress.substring(at + 1).toLowerCase();
    }
}
