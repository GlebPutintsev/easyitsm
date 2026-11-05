package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.UUID;

/**
 * Снимок заявки, который нужен SLA. Строится из событий модуля tickets: модуль sla не читает таблицы заявок.
 * Каждое событие несёт полный снимок, поэтому порядок доставки не важен: устаревшие снимки отбрасываются
 * по {@code occurredAt}.
 *
 * @param occurredAt      время бизнес-события в заявке
 * @param createdAt       время создания заявки, от него стартует SLA
 * @param statusCategory  категория статуса поверх настраиваемых статусов, см. {@link StatusCategory}
 * @param statusChangedAt когда заявка перешла в текущий статус; начало паузы
 * @param pauseReason     причина ожидания (SLA-04)
 * @param respondedAt     время первой реакции; останавливает счётчик реакции
 * @param resolvedAt      время решения; останавливает счётчик решения
 */
record TicketFacts(
        UUID ticketId,
        Instant occurredAt,
        Instant createdAt,
        String type,
        String priority,
        UUID serviceId,
        UUID clientId,
        UUID groupId,
        String statusCode,
        String statusCategory,
        Instant statusChangedAt,
        String pauseReason,
        Instant respondedAt,
        Instant resolvedAt) {

    Instant statusSince() {
        return statusChangedAt != null ? statusChangedAt : occurredAt;
    }
}
