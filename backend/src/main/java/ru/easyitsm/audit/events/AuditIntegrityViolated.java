package ru.easyitsm.audit.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Проверка целостности нашла нарушение цепочки журнала аудита (T-18). Для оповещения администратора
 * безопасности и SIEM.
 *
 * @param brokenAtSeq номер первой записи, на которой цепочка не сходится
 * @param problem     hash_mismatch, link_mismatch, gap или truncated
 */
public record AuditIntegrityViolated(UUID tenantId, long brokenAtSeq, String problem, Instant detectedAt) {
}
