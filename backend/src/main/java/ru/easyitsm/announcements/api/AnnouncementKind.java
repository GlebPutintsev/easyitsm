package ru.easyitsm.announcements.api;

public enum AnnouncementKind {
    INFO, MAINTENANCE, OUTAGE;

    public String code() {
        return name().toLowerCase();
    }

    public static AnnouncementKind fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
