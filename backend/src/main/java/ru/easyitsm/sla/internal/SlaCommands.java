package ru.easyitsm.sla.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Запросы REST API модуля. В PATCH null означает «не менять». */
final class SlaCommands {

    /** Действие при пороге или нарушении. */
    static final String ACTION = "notify:(assignee|group|group_lead|requester|watchers)"
            + "|escalate:(hierarchical|[0-9a-fA-F-]{36})|set_priority:[A-Za-z0-9_-]{1,32}";
    static final String CODE = "[A-Za-z0-9_.-]{1,64}";

    private SlaCommands() {
    }

    record ScheduleCreate(
            @NotBlank @Size(max = 255) String name,
            @NotBlank String timezone,
            @NotNull Map<DayOfWeek, List<String>> week) {
    }

    record ScheduleUpdate(
            @Size(min = 1, max = 255) String name,
            String timezone,
            Map<DayOfWeek, List<String>> week) {
    }

    record HolidayInput(
            @NotNull LocalDate date,
            @Size(max = 255) String name,
            /** Пусто — выходной; иначе особые часы (сокращённый или перенесённый рабочий день). */
            List<String> hours) {
    }

    record TargetInput(
            @NotNull @Pattern(regexp = "response|resolution") String metric,
            @NotNull @Pattern(regexp = CODE) String priority,
            @Min(1) @Max(1_000_000) int amount,
            @NotNull @Pattern(regexp = "minutes|hours|business_days") String unit,
            @Pattern(regexp = "policy|24x7") String calendar,
            List<@Min(1) @Max(99) Integer> thresholds,
            List<@Pattern(regexp = ACTION) String> warnActions,
            List<@Pattern(regexp = ACTION) String> breachActions) {
    }

    record ConditionsInput(
            List<@Pattern(regexp = CODE) String> ticketTypes,
            List<@Pattern(regexp = CODE) String> priorities,
            List<UUID> serviceIds,
            List<UUID> clientIds,
            List<UUID> groupIds) {
    }

    record PolicyCreate(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            @NotNull @Pattern(regexp = "sla|ola|uc") String kind,
            Boolean active,
            @Min(0) @Max(100_000) Integer rank,
            UUID scheduleId,
            @Valid ConditionsInput conditions,
            List<@Pattern(regexp = CODE) String> pauseStatuses,
            @NotEmpty List<@Valid TargetInput> targets) {
    }

    record PolicyUpdate(
            @Size(min = 1, max = 255) String name,
            @Size(max = 2000) String description,
            Boolean active,
            @Min(0) @Max(100_000) Integer rank,
            UUID scheduleId,
            Boolean clearSchedule,
            @Valid ConditionsInput conditions,
            List<@Pattern(regexp = CODE) String> pauseStatuses,
            @Size(min = 1) List<@Valid TargetInput> targets) {
    }

    /** «Проверить на примере»: заявка с такими параметрами, созданная в {@code startAt}, с паузами. */
    record PreviewInput(
            @NotNull @Pattern(regexp = CODE) String priority,
            @NotNull Instant startAt,
            List<@Valid Interval> pauses) {
    }

    record Interval(@NotNull Instant from, @NotNull Instant to) {
    }
}
