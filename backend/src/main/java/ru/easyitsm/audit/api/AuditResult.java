package ru.easyitsm.audit.api;

/** Результат действия в записи журнала (SEC-30). */
public enum AuditResult {
    SUCCESS, FAILURE, DENIED;

    public String code() {
        return name().toLowerCase();
    }
}
