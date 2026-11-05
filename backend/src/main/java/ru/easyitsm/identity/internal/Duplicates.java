package ru.easyitsm.identity.internal;

import java.sql.SQLException;
import java.util.function.Supplier;
import ru.easyitsm.platform.web.ApiException;

/** Нарушение уникальности (логин, почта, имя группы) превращается в понятный 409. */
final class Duplicates {

    private Duplicates() {
    }

    static <T> T guard(Supplier<T> action, String code, String detail) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof org.springframework.dao.DuplicateKeyException
                        || (t instanceof SQLException sql && "23505".equals(sql.getSQLState()))) {
                    throw ApiException.conflict(code, detail);
                }
            }
            throw e;
        }
    }
}
