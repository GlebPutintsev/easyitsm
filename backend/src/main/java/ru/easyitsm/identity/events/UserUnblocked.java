package ru.easyitsm.identity.events;

import java.util.UUID;

public record UserUnblocked(UUID tenantId, UUID userId, UUID actorId) {
}
