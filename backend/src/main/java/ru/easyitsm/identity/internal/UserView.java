package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.UUID;

/** Пользователь в API «Управления». */
record UserView(
        UUID id,
        String login,
        String email,
        String displayName,
        String locale,
        String timezone,
        String state,
        String blockedReason,
        String source,
        UUID organizationId,
        UUID departmentId,
        UUID managerId,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
