package ru.easyitsm.identity.events;

import java.util.UUID;

/** Пользователь добавлен в группу ({@code added = true}) или удалён из неё. */
public record GroupMembershipChanged(UUID tenantId, UUID groupId, UUID userId, boolean added, UUID actorId) {
}
