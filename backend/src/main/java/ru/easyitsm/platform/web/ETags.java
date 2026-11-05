package ru.easyitsm.platform.web;

import org.springframework.http.HttpStatus;

/** ETag по версии записи и проверка {@code If-Match} (API-05). */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Проверяет {@code If-Match} перед изменением. Без заголовка — 428, с устаревшей версией — 412.
     */
    public static void requireMatch(String ifMatch, Long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if_match_required",
                    "Нужен заголовок If-Match с ETag записи");
        }
        if (!ifMatch.trim().equals(of(currentVersion))) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "version_conflict",
                    "Запись изменена другим пользователем. Обновите и повторите");
        }
    }
}
