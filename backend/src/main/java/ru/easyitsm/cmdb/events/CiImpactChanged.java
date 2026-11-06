package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Изменился граф влияния ({@code ci.impact_changed} в разделе 6.3 ТЗ): добавлена или снята связь, по которой
 * идёт влияние, либо КЕ выведена из эксплуатации или слита. Слушатели (изменения, крупные инциденты)
 * пересчитывают влияние для своих записей, если пересекаются с {@code ciIds}.
 *
 * @param ciIds  КЕ на концах изменившихся связей
 * @param reason {@code relation_added}, {@code relation_removed}, {@code status_changed}, {@code merged}
 */
public record CiImpactChanged(UUID tenantId, Instant occurredAt, UUID actorId, String source, Set<UUID> ciIds,
                              String reason) {
}
