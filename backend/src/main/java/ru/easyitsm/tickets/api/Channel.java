package ru.easyitsm.tickets.api;

/** Канал, через который создана заявка (INC-01). */
public enum Channel {
    PORTAL, EMAIL, API, CHAT, MONITORING, AGENT;

    public String code() {
        return Codes.code(this);
    }

    public static Channel fromCode(String code) {
        return Codes.parse(Channel.class, code, "канала");
    }
}
