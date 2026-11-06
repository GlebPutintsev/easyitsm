package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;
import ru.easyitsm.cmdb.api.CiStatus;

/** Сменился статус КЕ. Вывод из эксплуатации — {@code ci.status() == RETIRED}. */
public record CiStatusChanged(UUID tenantId, Instant occurredAt, UUID actorId, String source, CiRef ci,
                              CiStatus previous) implements CiEvent {
}
