package ru.easyitsm.platform.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Расширение базовой цепочки безопасности модулем. Так модуль identity добавляет вход, сессии в куках и CSRF,
 * не меняя ядро платформы.
 * <p>
 * Вызывается до правил платформы, поэтому правила доступа, заданные здесь через
 * {@code authorizeHttpRequests}, проверяются раньше общих («всё под /api требует входа», «остальное запрещено»).
 */
@FunctionalInterface
public interface SecurityChainCustomizer {

    void customize(HttpSecurity http) throws Exception;
}
