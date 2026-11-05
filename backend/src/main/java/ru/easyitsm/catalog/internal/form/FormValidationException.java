package ru.easyitsm.catalog.internal.form;

import java.util.Map;

/** Ошибки по полям формы: ключ поля → код ошибки. Отдаётся как 400 с {@code errors} (UI-06). */
public class FormValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public FormValidationException(Map<String, String> errors) {
        super("Проверьте заполнение формы");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
