package ru.easyitsm.platform.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;

/**
 * Определяет арендатора HTTP-запроса. Реализации опрашиваются по {@link org.springframework.core.annotation.Order}.
 * Модуль identity добавит реализацию, которая берёт арендатора из токена пользователя.
 */
public interface TenantResolver {

    Optional<UUID> resolve(HttpServletRequest request);
}
