package ru.easyitsm.identity.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Действие требует повторной аутентификации (SEC-08): назначение ролей, удаление, экспорт, согласование
 * с высоким риском. Ставится на метод контроллера.
 * <p>
 * Если пользователь подтверждал вход позже, чем {@code easyitsm.identity.session.reauth-window} назад,
 * запрос выполняется. Иначе ответ 401 с кодом {@code reauth_required}: интерфейс показывает окно
 * подтверждения, вызывает {@code POST /api/v1/auth/reauth} и повторяет запрос.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRecentAuth {
}
