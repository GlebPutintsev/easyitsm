package ru.easyitsm.identity.events;

import java.time.Instant;
import java.util.UUID;

/** Роль выдана пользователю или группе. Ровно одно из userId и groupId задано. */
public record RoleGranted(UUID tenantId, UUID grantId, String role, UUID userId, UUID groupId, UUID grantedBy,
                          String reason, Instant expiresAt) {
}
