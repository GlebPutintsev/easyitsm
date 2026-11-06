package ru.easyitsm.sla.internal;

import java.util.Set;

/**
 * Коды категорий статуса заявки ({@code ru.easyitsm.tickets.api.StatusCategory#code()}), на которые опирается SLA.
 * Сами статусы настраиваются администратором, категории фиксированы.
 */
final class StatusCategory {

    static final String DRAFT = "draft";
    static final String APPROVAL = "approval";
    static final String PENDING = "pending";
    static final String RESOLVED = "resolved";
    static final String CLOSED = "closed";
    static final String CANCELLED = "cancelled";

    /** Заявка ещё не передана в работу: черновик или запрос на согласовании. SLA не стартует и стоит. */
    static final Set<String> NOT_STARTED = Set.of(DRAFT, APPROVAL);

    private StatusCategory() {
    }
}
