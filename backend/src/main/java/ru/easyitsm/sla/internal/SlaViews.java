package ru.easyitsm.sla.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.sla.api.SlaInstanceView;

/** Ответы REST API модуля. */
final class SlaViews {

    private SlaViews() {
    }

    record Schedule(UUID id, String name, String timezone, Map<DayOfWeek, List<String>> week, Instant updatedAt,
                    long version) {
    }

    record HolidayItem(LocalDate date, String name, List<String> hours) {
    }

    record Conditions(List<String> ticketTypes, List<String> priorities, List<UUID> serviceIds, List<UUID> clientIds,
                      List<UUID> groupIds) {
    }

    record Target(String metric, String priority, int amount, String unit, String calendar, List<Integer> thresholds,
                  List<String> warnActions, List<String> breachActions) {
    }

    record Policy(UUID id, String name, String description, String kind, boolean active, int rank, UUID scheduleId,
                  Conditions conditions, List<String> pauseStatuses, List<Target> targets, Instant updatedAt,
                  long version) {
    }

    record Pause(String kind, Instant startedAt, Instant endedAt, String reason) {
    }

    record HistoryItem(String type, Instant occurredAt, String reason, String from, String to, Instant oldDueAt,
                       Instant newDueAt) {
    }

    record InstanceDetail(SlaInstanceView instance, List<Pause> pauses, List<HistoryItem> history) {
    }

    /** Результат «Проверить на примере»: какие сроки получит заявка с заданными параметрами. */
    record Preview(UUID policyId, String policyName, String kind, List<PreviewTarget> targets) {
    }

    record PreviewTarget(String metric, String priority, long targetSeconds, Instant dueAt, Map<Integer, Instant> thresholds) {
    }
}
