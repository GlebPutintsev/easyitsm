package ru.easyitsm.catalog.api;

import java.util.UUID;

/** Согласования для других модулей: счётчик в верхней панели, мобильное меню. */
public interface ApprovalQueries {

    /** Сколько согласований ждут решения пользователя. */
    long pendingCount(UUID approverId);
}
