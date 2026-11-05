package ru.easyitsm.identity.events;

import java.util.UUID;

/** Пользователь создан: вручную, синхронизацией LDAP или при первом входе через SSO. */
public record UserCreated(UUID tenantId, UUID userId, String login, String source) {
}
