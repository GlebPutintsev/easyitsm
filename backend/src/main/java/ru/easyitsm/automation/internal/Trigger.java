package ru.easyitsm.automation.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Событие, на которое срабатывает правило. Каждое добавляет к полям заявки свои поля для условий.
 */
enum Trigger {
    TICKET_CREATED(Set.of()),
    STATUS_CHANGED(Set.of("fromStatusCode", "fromStatusCategory", "transitionTrigger")),
    PRIORITY_CHANGED(Set.of("fromPriority")),
    ASSIGNMENT_CHANGED(Set.of("fromGroupId", "fromAssigneeId")),
    TICKET_UPDATED(Set.of("changedFields")),
    COMMENT_ADDED(Set.of("visibility", "fromRequester")),
    ESCALATED(Set.of("escalationKind", "escalationLevel")),
    SLA_WARNING(Set.of("slaKind", "slaMetric", "slaPercent")),
    SLA_BREACHED(Set.of("slaKind", "slaMetric"));

    private final Set<String> eventFields;

    Trigger(Set<String> eventFields) {
        this.eventFields = eventFields;
    }

    /** Поля события, доступные в условиях помимо полей заявки {@link Facts#TICKET_FIELDS}. */
    Set<String> eventFields() {
        return eventFields;
    }

    boolean allows(String field) {
        return Facts.TICKET_FIELDS.contains(field) || eventFields.contains(field);
    }

    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Optional<Trigger> of(String code) {
        return Arrays.stream(values()).filter(t -> t.code().equals(code)).findFirst();
    }
}
