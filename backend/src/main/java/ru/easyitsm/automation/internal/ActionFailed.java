package ru.easyitsm.automation.internal;

/** Действие правила не выполнено; транзакция запуска откатывается, в журнал пишется failed. */
class ActionFailed extends RuntimeException {

    private final int index;
    private final String type;
    private final String code;

    ActionFailed(int index, String type, String code, String message, Throwable cause) {
        super(message, cause);
        this.index = index;
        this.type = type;
        this.code = code;
    }

    int index() {
        return index;
    }

    String type() {
        return type;
    }

    String code() {
        return code;
    }
}
