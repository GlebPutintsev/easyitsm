package ru.easyitsm.identity.api;

import java.util.UUID;

/** Группа для других модулей: назначенная группа заявки, группа безопасности, группа согласования. */
public record GroupSummary(UUID id, String name, String type) {
}
