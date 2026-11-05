package ru.easyitsm.platform.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Позиция в упорядоченной выборке: значение ключа сортировки и id для однозначности.
 * Клиент получает непрозрачную строку base64url.
 */
public record KeysetCursor(String sortValue, UUID id) {

    public String encode() {
        String raw = sortValue + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static KeysetCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf('|');
            return new KeysetCursor(raw.substring(0, sep), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_cursor", "Некорректный курсор");
        }
    }
}
