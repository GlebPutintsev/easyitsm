package ru.easyitsm.identity.events;

import java.util.UUID;

public record RoleRevoked(UUID tenantId, UUID grantId, String role, UUID userId, UUID groupId, UUID revokedBy) {
}
