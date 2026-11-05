package ru.easyitsm.platform.web;

import java.util.List;
import java.util.function.Function;

/** Страница курсорной постраничности (API-03). {@code nextCursor == null} — последняя страница. */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor);
    }

    /**
     * Собирает страницу из выборки размером {@code limit + 1}: лишняя строка означает, что есть следующая страница.
     */
    public static <T> CursorPage<T> of(List<T> fetched, int limit, Function<T, KeysetCursor> cursorOf) {
        if (fetched.size() <= limit) {
            return new CursorPage<>(List.copyOf(fetched), null);
        }
        List<T> page = List.copyOf(fetched.subList(0, limit));
        return new CursorPage<>(page, cursorOf.apply(page.get(limit - 1)).encode());
    }
}
