package ru.easyitsm.platform.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Пользователь или сервисная учётная запись, от имени которой выполняется запрос.
 * Модуль identity кладёт его в {@code Authentication#getPrincipal()} после входа.
 */
public record ActorPrincipal(UUID userId, UUID tenantId, String login) implements Principal {

    @Override
    public String getName() {
        return login;
    }
}
