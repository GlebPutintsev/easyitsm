package ru.easyitsm.identity.api;

import java.util.UUID;

/** Сведения о пользователе для других модулей: заявитель, исполнитель, согласующий. */
public record UserSummary(
        UUID id,
        String login,
        String displayName,
        String email,
        String locale,
        String timezone,
        boolean active,
        UUID organizationId,
        UUID departmentId,
        UUID managerId) {
}
