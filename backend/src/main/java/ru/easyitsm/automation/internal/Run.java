package ru.easyitsm.automation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Запись журнала запусков (AUT-04).
 *
 * @param ruleId     null — правило удалено или запуск встроенный (действие из настройки SLA)
 * @param depth      глубина цепочки: 1 — событие от человека или таймера, 2 — от действия правила первого уровня…
 * @param chain      правила, действия которых привели к этому запуску, от первого
 * @param status     success, failed, skipped
 * @param skipReason depth_limit, loop, rate_limit — почему защита от петель не дала выполнить действия
 * @param results    итог каждого действия: {@code type}, {@code outcome} (applied, unchanged), {@code detail}
 */
record Run(
        UUID id,
        UUID ruleId,
        String ruleName,
        long ruleVersion,
        String trigger,
        UUID ticketId,
        String ticketNumber,
        int depth,
        List<UUID> chain,
        String status,
        String skipReason,
        List<Map<String, Object>> results,
        String errorCode,
        String error,
        Instant startedAt,
        int durationMs) {
}
