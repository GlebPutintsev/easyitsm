package ru.easyitsm.tickets.api;

/** Тип заявки (TCK-01) и префикс номера (TCK-02). */
public enum TicketType {
    INCIDENT("INC"), REQUEST("REQ"), PROBLEM("PRB"), CHANGE("CHG"), TASK("TSK");

    private final String prefix;

    TicketType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public String code() {
        return Codes.code(this);
    }

    public static TicketType fromCode(String code) {
        return Codes.parse(TicketType.class, code, "типа заявки");
    }
}
