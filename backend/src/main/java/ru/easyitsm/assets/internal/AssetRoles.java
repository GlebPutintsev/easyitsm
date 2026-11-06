package ru.easyitsm.assets.internal;

/** Роли раздела 3.1 ТЗ для эндпоинтов модуля. */
final class AssetRoles {

    /** Читают активы: менеджер активов, администратор, поддержка (карточка заявки), менеджер конфигураций, аудитор. */
    static final String READ = "hasAnyRole('AST', 'ADM', 'AGT', 'LEAD', 'CFG', 'AUD')";

    /** Ведут учёт: менеджер активов (AST) и администратор. */
    static final String MANAGE = "hasAnyRole('AST', 'ADM')";

    /** Связывают актив с заявкой: ещё и агенты, если видят заявку. */
    static final String LINK = "hasAnyRole('AST', 'ADM', 'AGT', 'LEAD')";

    private AssetRoles() {
    }
}
