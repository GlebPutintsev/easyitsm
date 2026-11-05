package ru.easyitsm.identity.events;

import java.util.UUID;

/** Неудачная попытка входа. userId пуст, если логин не найден. */
public record LoginFailed(UUID tenantId, UUID userId, String login, String method, String ip) {
}
