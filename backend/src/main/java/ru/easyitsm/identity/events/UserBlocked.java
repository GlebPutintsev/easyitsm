package ru.easyitsm.identity.events;

import java.util.UUID;

/** Пользователь заблокирован, его сессии завершены (ACC-10). reason: admin или source. */
public record UserBlocked(UUID tenantId, UUID userId, String reason, UUID actorId) {
}
