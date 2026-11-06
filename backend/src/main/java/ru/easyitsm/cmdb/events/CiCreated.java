package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;

/** КЕ создана вручную или при загрузке из источника. */
public record CiCreated(UUID tenantId, Instant occurredAt, UUID actorId, String source, CiRef ci)
        implements CiEvent {
}
