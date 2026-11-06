package ru.easyitsm.cmdb.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import ru.easyitsm.platform.web.ApiException;

/** Ошибка API CMDB с полем {@code field} в теле RFC 9457. */
class CmdbProblem extends ApiException {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    CmdbProblem(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }

    CmdbProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    Map<String, Object> properties() {
        return properties;
    }

    static CmdbProblem invalid(String code, String field, String detail) {
        return new CmdbProblem(HttpStatus.BAD_REQUEST, code, detail).with("field", field);
    }

    static CmdbProblem clash(String code, String detail) {
        return new CmdbProblem(HttpStatus.CONFLICT, code, detail);
    }
}
