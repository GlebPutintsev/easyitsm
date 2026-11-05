package ru.easyitsm.identity.events;

import java.time.Instant;
import java.util.UUID;

/** Учётная запись временно заблокирована после серии неудачных входов (SEC-05). Оповещение владельца и SEC. */
public record AccountLocked(UUID tenantId, UUID userId, Instant lockedUntil, String ip) {
}
