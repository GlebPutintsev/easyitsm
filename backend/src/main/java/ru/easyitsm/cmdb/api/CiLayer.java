package ru.easyitsm.cmdb.api;

/**
 * Уровень сервисной модели (CFG-02). Задаётся классом КЕ и наследуется подклассами.
 * Отказ распространяется снизу вверх: инфраструктура → приложение → техническая услуга → бизнес-услуга.
 */
public enum CiLayer {
    BUSINESS_SERVICE, TECHNICAL_SERVICE, APPLICATION, INFRASTRUCTURE;

    public String code() {
        return name().toLowerCase();
    }

    public boolean isService() {
        return this == BUSINESS_SERVICE || this == TECHNICAL_SERVICE;
    }

    public static CiLayer fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
