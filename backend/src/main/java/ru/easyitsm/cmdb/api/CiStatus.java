package ru.easyitsm.cmdb.api;

/** Жизненный цикл КЕ. Удаления нет: КЕ выводится из эксплуатации ({@link #RETIRED}) или сливается при сверке. */
public enum CiStatus {
    PLANNED, ACTIVE, MAINTENANCE, RETIRED;

    public String code() {
        return name().toLowerCase();
    }

    public static CiStatus fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
