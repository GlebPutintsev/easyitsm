package ru.easyitsm.identity.internal;

import org.springframework.http.HttpStatus;
import ru.easyitsm.platform.web.ApiException;

/**
 * Неудачный вход. Сообщение всегда одно и то же и не раскрывает причину (10.3 ТЗ): неверный пароль,
 * несуществующий логин и заблокированная учётная запись выглядят одинаково.
 * {@code failures} — сколько неудач подряд, по нему контроллер выбирает задержку ответа (SEC-05).
 */
class AuthFailure extends ApiException {

    private final int failures;

    AuthFailure(int failures) {
        super(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Неверный логин или пароль");
        this.failures = failures;
    }

    static AuthFailure invalidCode(int failures) {
        return new AuthFailure(failures, "invalid_code", "Неверный код");
    }

    private AuthFailure(int failures, String code, String detail) {
        super(HttpStatus.UNAUTHORIZED, code, detail);
        this.failures = failures;
    }

    int failures() {
        return failures;
    }
}
