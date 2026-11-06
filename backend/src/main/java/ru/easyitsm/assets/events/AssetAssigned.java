package ru.easyitsm.assets.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Актив выдан или передан сотруднику ({@code asset.assigned}, раздел 6.3 ТЗ).
 *
 * @param previousHolderId прежний сотрудник при передаче, null при выдаче со склада
 */
public record AssetAssigned(UUID tenantId, Instant occurredAt, UUID actorId, UUID assetId, String tag, String name,
                            UUID holderId, UUID previousHolderId) {
}
