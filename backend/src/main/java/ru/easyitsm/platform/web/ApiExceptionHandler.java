package ru.easyitsm.platform.web;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Все ошибки API в формате RFC 9457 с полями {@code code} и {@code trace_id} (API-02). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://easyitsm.ru/problems/";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<Object> handleDenied(RuntimeException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Недостаточно прав");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "version_conflict", "Запись изменена другим пользователем. Обновите и повторите");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Нарушение целостности данных: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "integrity_violation", "Операция нарушает ограничения данных");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Необработанная ошибка", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Внутренняя ошибка. Сообщите код трассировки в поддержку");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            pd.setProperty("trace_id", TraceIdFilter.current());
            if (pd.getProperties() == null || !pd.getProperties().containsKey("code")) {
                pd.setProperty("code", HttpStatus.valueOf(statusCode.value()).name().toLowerCase());
            }
        }
        return response;
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + code));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("trace_id", TraceIdFilter.current());
        return ResponseEntity.status(status).body(pd);
    }
}
