package ru.easyitsm.problems.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Проблема решена ({@code problem.resolved}, 6.3 ТЗ): заявка проблемы перешла в категорию resolved.
 *
 * @param resolvedIncidents сколько связанных инцидентов модуль решил вместе с ней (PRB-04)
 */
public record ProblemResolved(UUID tenantId, Instant occurredAt, UUID problemId, String number,
                              int resolvedIncidents) {
}
