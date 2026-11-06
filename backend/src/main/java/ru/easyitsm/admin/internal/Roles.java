package ru.easyitsm.admin.internal;

/** Выражения прав «Управления» (раздел 3.2 ТЗ: «Настроить процессы и формы» — ADM). */
final class Roles {

    private Roles() {
    }

    static final String ADMIN = "hasRole('ADM')";
    /** Журнал действий администратора и версии настроек читают ADM, SEC и аудитор. */
    static final String JOURNAL = "hasAnyRole('ADM', 'SEC', 'AUD')";
    /** Справочники нужны всем сотрудникам поддержки: агент выбирает код решения. */
    static final String STAFF = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST', 'ANL', 'ADM', 'SEC', 'AUD')";
}
