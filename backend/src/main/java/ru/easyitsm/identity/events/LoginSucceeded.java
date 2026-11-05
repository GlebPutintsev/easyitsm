package ru.easyitsm.identity.events;

import java.util.UUID;

/** Вход завершён (после MFA, если она нужна). Для журнала аудита и оповещений о новом входе. */
public record LoginSucceeded(UUID tenantId, UUID userId, UUID sessionId, String method, String ip) {
}
