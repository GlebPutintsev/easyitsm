package ru.easyitsm.changes.internal;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.easyitsm.platform.web.TraceIdFilter;

/** Отдаёт {@link ChangeProblem} в формате RFC 9457 вместе с его полями. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ChangeProblemAdvice {

    @ExceptionHandler(ChangeProblem.class)
    ResponseEntity<ProblemDetail> handle(ChangeProblem ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setType(URI.create("https://easyitsm.ru/problems/" + ex.code()));
        pd.setTitle(ex.status().getReasonPhrase());
        pd.setProperty("code", ex.code());
        pd.setProperty("trace_id", TraceIdFilter.current());
        ex.properties().forEach(pd::setProperty);
        return ResponseEntity.status(ex.status()).body(pd);
    }
}
