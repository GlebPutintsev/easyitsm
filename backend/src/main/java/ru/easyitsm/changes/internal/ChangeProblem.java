package ru.easyitsm.changes.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import ru.easyitsm.platform.web.ApiException;

/** Ошибка API изменений с дополнительными полями в теле RFC 9457: {@code field}, {@code missing}, {@code conflicts}. */
class ChangeProblem extends ApiException {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    ChangeProblem(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }

    ChangeProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    Map<String, Object> properties() {
        return properties;
    }

    static ChangeProblem field(String field, String detail) {
        return new ChangeProblem(HttpStatus.BAD_REQUEST, "field_required", detail).with("field", field);
    }

    static ChangeProblem invalid(String code, String detail) {
        return new ChangeProblem(HttpStatus.BAD_REQUEST, code, detail);
    }

    static ChangeProblem clash(String code, String detail) {
        return new ChangeProblem(HttpStatus.CONFLICT, code, detail);
    }
}
