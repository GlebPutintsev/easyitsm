package ru.easyitsm.assets.events;

import java.time.Instant;
import java.util.UUID;

/** Актив списан с указанием причины. Занятые им лицензии освобождены. */
public record AssetRetired(UUID tenantId, Instant occurredAt, UUID actorId, UUID assetId, String tag, String reason) {
}
