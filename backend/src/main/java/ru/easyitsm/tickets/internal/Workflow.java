package ru.easyitsm.tickets.internal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Жизненный цикл типа заявки (раздел 5). Хранится документом JSONB {@code workflow.<type>}, без строки —
 * {@link DefaultWorkflows}. Коды в нижнем регистре, как в REST.
 */
record Workflow(String type, List<Status> statuses, List<Transition> transitions, Long version) {

    static final Set<String> TRIGGERS = Set.of("manual", "group_assigned", "requester_reply", "timer", "approval");
    static final Set<String> REQUIRED_FIELDS = Set.of("comment", "reason_code", "resolution", "pending_until", "group",
            "assignee");

    Workflow {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    record Status(String code, String name, String category, Boolean initial) {

        StatusCategory categoryEnum() {
            return StatusCategory.fromCode(category);
        }
    }

    record Transition(String from, String to, String name, List<String> roles, List<String> requiredFields,
                      String trigger) {

        Transition {
            roles = roles == null ? List.of() : List.copyOf(roles);
            requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
            trigger = trigger == null ? "manual" : trigger;
        }

        boolean manual() {
            return "manual".equals(trigger);
        }
    }

    TicketType typeEnum() {
        return TicketType.fromCode(type);
    }

    Workflow withVersion(Long v) {
        return new Workflow(type, statuses, transitions, v);
    }

    Optional<Status> status(String code) {
        return statuses.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    Status require(String code) {
        return status(code).orElseThrow(() -> new IllegalStateException("Статус " + code + " отсутствует в цикле " + type));
    }

    Status initial() {
        return statuses.stream().filter(s -> Boolean.TRUE.equals(s.initial())).findFirst().orElse(statuses.getFirst());
    }

    Optional<Status> firstOf(StatusCategory category) {
        return statuses.stream().filter(s -> s.categoryEnum() == category).findFirst();
    }

    List<Transition> from(String statusCode) {
        return transitions.stream().filter(t -> t.from().equals(statusCode)).toList();
    }

    /** Автоматический переход по триггеру из статуса, если в цикле он есть. */
    Optional<Transition> auto(String fromStatus, String trigger) {
        return transitions.stream()
                .filter(t -> t.from().equals(fromStatus) && Objects.equals(t.trigger(), trigger))
                .findFirst();
    }
}
