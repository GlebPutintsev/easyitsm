package ru.easyitsm.licensing.internal;

/** Файл лицензии не прошёл проверку. {@code code} — стабильный код причины для API и журнала. */
class LicenseException extends Exception {

    private final String code;

    LicenseException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
