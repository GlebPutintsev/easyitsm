package ru.easyitsm.platform.web;

/** Параметры страницы: по умолчанию 50, максимум 200 (API-03). */
public record PageParams(String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public static PageParams of(String cursor, Integer limit) {
        int l = limit == null ? DEFAULT_LIMIT : limit;
        if (l < 1 || l > MAX_LIMIT) {
            throw ApiException.badRequest("invalid_limit", "limit должен быть от 1 до " + MAX_LIMIT);
        }
        return new PageParams(cursor == null || cursor.isBlank() ? null : cursor, l);
    }

    public KeysetCursor keyset() {
        return cursor == null ? null : KeysetCursor.decode(cursor);
    }
}
