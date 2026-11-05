package ru.easyitsm.tickets.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.SecretFinding;

/** Ошибка API с дополнительными полями в теле RFC 9457: {@code field}, {@code findings}. */
class TicketProblem extends ApiException {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    TicketProblem(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }

    TicketProblem with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    Map<String, Object> properties() {
        return properties;
    }

    static TicketProblem fieldRequired(String field, String detail) {
        return new TicketProblem(HttpStatus.BAD_REQUEST, "field_required", detail).with("field", field);
    }

    static TicketProblem invalidCustom(String field, String detail) {
        return new TicketProblem(HttpStatus.BAD_REQUEST, "invalid_custom_field", detail).with("field", field);
    }

    static TicketProblem secrets(List<SecretFinding> findings) {
        return new TicketProblem(HttpStatus.UNPROCESSABLE_CONTENT, "secrets_detected",
                "В тексте найдены пароли или ключи. Скройте их или подтвердите отправку")
                .with("findings", findings);
    }

    static TicketProblem clash(String code, String detail) {
        return new TicketProblem(HttpStatus.CONFLICT, code, detail);
    }
}
