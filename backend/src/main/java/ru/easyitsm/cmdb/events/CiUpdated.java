package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;

/**
 * Изменены поля КЕ ({@code ci.updated} в разделе 6.3 ТЗ): {@code name}, {@code class}, {@code environment},
 * {@code criticality}, {@code owner_group}, {@code description}, {@code attr.<код>}. Смена статуса — отдельным
 * {@link CiStatusChanged}.
 */
public record CiUpdated(UUID tenantId, Instant occurredAt, UUID actorId, String source, CiRef ci,
                        Set<String> fields) implements CiEvent {
}
