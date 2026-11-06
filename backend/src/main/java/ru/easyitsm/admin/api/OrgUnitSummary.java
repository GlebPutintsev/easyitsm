package ru.easyitsm.admin.api;

import java.util.UUID;

/** Организация или подразделение. kind — organization или department. */
public record OrgUnitSummary(UUID id, String kind, String name, String code, UUID parentId, boolean active) {
}
