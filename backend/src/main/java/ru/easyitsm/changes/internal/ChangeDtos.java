package ru.easyitsm.changes.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Запросы и ответы REST изменений: {@code /api/v1/changes}. */
final class ChangeDtos {

    private ChangeDtos() {
    }

    // ================================================================ запросы

    record Create(
            @Pattern(regexp = "standard|normal|emergency") String type,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20_000) String description,
            UUID templateId,
            UUID sourceTicketId,
            UUID groupId,
            @Size(max = 100) List<UUID> ciIds,
            @Size(max = 100) List<UUID> serviceIds) {
    }

    /** Частичное изменение полей: null — не менять. */
    record Update(
            @Pattern(regexp = "standard|normal|emergency") String type,
            @Size(max = 20_000) String goal,
            @Size(max = 20_000) String justification,
            @Size(max = 20_000) String impact,
            @Size(max = 20_000) String risks,
            @Size(max = 50) List<@Valid ImpactRow> impactRows,
            @Pattern(regexp = "critical|high|medium|low|") String systemCriticality,
            Instant windowStart,
            Instant windowEnd,
            @Size(max = 64) String timeZone,
            Instant prepStart,
            Boolean clearPrepStart,
            @Min(0) @Max(100_000) Integer downtimeMinutes,
            @Size(max = 100) List<UUID> ciIds,
            @Size(max = 100) List<UUID> serviceIds) {
    }

    /** Строка таблицы влияния на ИТ-сервисы. */
    record ImpactRow(@NotBlank @Size(max = 300) String service, @Size(max = 2000) String description,
                     @Size(max = 100) String duration) {
    }

    record StepInput(
            @NotNull @Pattern(regexp = "prepare|implement|verify|rollback") String phase,
            @NotBlank @Size(max = 500) String title,
            @Size(max = 20_000) String description,
            UUID ownerId,
            @Size(max = 200) String ownerName,
            @Size(max = 200) String team,
            @Size(max = 500) String contacts,
            Instant plannedStart,
            @Min(0) @Max(10_080) Integer durationMinutes,
            Boolean upto,
            @Size(max = 500) String interruption,
            @Size(max = 2000) String checkCriteria,
            @Min(1) Integer rollbackRef,
            /** Id существующего шага: результат выполнения сохраняется при перестановке. */
            UUID id) {
    }

    record Plan(@NotNull @Size(max = 300) List<@Valid StepInput> steps) {
    }

    record Risk(@NotNull Map<String, String> answers,
                @Pattern(regexp = "low|medium|high") String level,
                @Size(max = 2000) String reason) {
    }

    record Comment(@Size(max = 4000) String comment) {
    }

    record Reason(@NotBlank @Size(max = 4000) String reason) {
    }

    record SendToApproval(@Size(max = 4000) String acknowledgeConflicts, @Size(max = 20) List<UUID> extraApproverIds) {
    }

    record Decision(@NotNull @Pattern(regexp = "approve|reject") String decision, @Size(max = 4000) String comment) {
    }

    record Reschedule(@NotNull Instant windowStart, @NotNull Instant windowEnd, @NotBlank @Size(max = 4000) String reason,
                      @Size(max = 4000) String acknowledgeConflicts) {
    }

    record Start(@Size(max = 4000) String outsideWindowReason) {
    }

    record StepResult(@NotNull @Pattern(regexp = "done|failed|skipped|pending") String status,
                      @Size(max = 4000) String note) {
    }

    record Complete(@NotNull @Pattern(regexp = "successful|partial|rolled_back") String outcome,
                    Instant actualStart, Instant actualEnd, @Size(max = 20_000) String conclusion) {
    }

    /**
     * Разбор после работ (CHG-11), вопросы согласованной формы отчёта о выполнении работ.
     *
     * @param answers goalAchieved, enoughInfo, causedIncidents, incidentNumbers, incidentCauses, consequences,
     *                correctiveActions, onTime, impactAsPlanned, docsUpdated, rollbackWorked, comment
     */
    record Close(@NotNull Map<String, Object> answers, @NotBlank @Size(max = 20_000) String conclusion) {
    }

    record ConflictQuery(@NotNull Instant windowStart, @NotNull Instant windowEnd, List<UUID> ciIds,
                         List<UUID> serviceIds, UUID excludeId) {
    }

    // ================================================================ ответы

    record StepView(UUID id, String phase, int seq, String title, String description, UUID ownerId, String ownerName,
                    String team, String contacts, Instant plannedStart, Instant start, Instant end,
                    int durationMinutes, boolean upto, String interruption, String checkCriteria, Integer rollbackRef,
                    String status, String resultNote, Instant executedAt, UUID executedBy) {
    }

    /**
     * @param fitsWindow внедрение и проверка укладываются в окно работ; null — окна нет
     */
    record PlanTotals(int prepareMinutes, int implementMinutes, int verifyMinutes, int rollbackMinutes,
                      Instant planEnd, Boolean fitsWindow) {
    }

    record ApprovalView(UUID id, String kind, UUID groupId, UUID approverId, int round, boolean postFactum,
                        String decision, String comment, UUID decidedBy, Instant decidedAt, Instant dueAt,
                        boolean canDecide) {
    }

    record CabEntry(UUID meetingId, String meetingTitle, Instant startsAt, String meetingState, UUID itemId,
                    String decision, String comment) {
    }

    record RiskView(Map<String, Object> answers, Integer score, String level, boolean manual, String reason,
                    Instant assessedAt, UUID assessedBy, String computedLevel) {
    }

    record CiView(UUID id, String name, String className, boolean service) {
    }

    record Permissions(boolean edit, boolean editPlan, boolean submit, boolean assess, boolean sendToApproval,
                       boolean returnToDraft, boolean reschedule, boolean start, boolean recordSteps,
                       boolean startRollback, boolean complete, boolean close, boolean cancel) {
    }

    record ChangeView(
            UUID id,
            String number,
            String title,
            String type,
            String state,
            UUID templateId,
            UUID sourceTicketId,
            UUID authorId,
            UUID groupId,
            UUID assigneeId,
            boolean confidential,
            String goal,
            String justification,
            String impact,
            String risks,
            List<Map<String, Object>> impactRows,
            String systemCriticality,
            Instant windowStart,
            Instant windowEnd,
            String timeZone,
            Instant prepStart,
            Integer downtimeMinutes,
            List<UUID> ciIds,
            List<UUID> serviceIds,
            List<CiView> cis,
            RiskView risk,
            String conflictsAck,
            Instant submittedAt,
            Instant approvedAt,
            Instant postApprovalDueAt,
            boolean postApprovalOverdue,
            Instant actualStart,
            Instant actualEnd,
            Instant rollbackStartedAt,
            String outcome,
            String conclusion,
            Map<String, Object> review,
            Instant closedAt,
            String cancelReason,
            Map<String, Object> pipeline,
            List<StepView> steps,
            PlanTotals plan,
            List<ApprovalView> approvals,
            List<CabEntry> cab,
            List<String> missing,
            Permissions permissions,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    /** Строка списка и календаря. */
    record ChangeItem(UUID id, String number, String title, String type, String state, String riskLevel,
                      Instant windowStart, Instant windowEnd, UUID authorId, UUID groupId, UUID assigneeId,
                      List<UUID> ciIds, List<UUID> serviceIds, String outcome, Instant updatedAt) {

        static ChangeItem of(ChangeRow c) {
            return new ChangeItem(c.id, c.number, c.title, c.type.code(), c.state.code(),
                    c.riskLevel == null ? null : c.riskLevel.code(), c.windowStart, c.windowEnd, c.authorId,
                    c.groupId, c.assigneeId, c.ciIds, c.serviceIds, c.outcome == null ? null : c.outcome.code(),
                    c.updatedAt);
        }
    }

    /**
     * Конфликт окна (CHG-05, AT-06).
     *
     * @param kind {@code ci} — общая КЕ, {@code service} — общая услуга, {@code freeze} — период заморозки
     */
    record Conflict(String kind, UUID changeId, String number, String title, String state, UUID freezeId,
                    List<UUID> sharedCiIds, List<UUID> sharedServiceIds, Instant from, Instant to) {
    }

    record FreezeView(UUID id, String title, String reason, Instant startsAt, Instant endsAt, String scope,
                      List<UUID> serviceIds, List<UUID> ciIds, long version) {

        static FreezeView of(FreezeStore.Freeze f) {
            return new FreezeView(f.id(), f.title(), f.reason(), f.startsAt(), f.endsAt(), f.scope(), f.serviceIds(),
                    f.ciIds(), f.version());
        }
    }

    record Calendar(Instant from, Instant to, List<ChangeItem> changes, List<FreezeView> freezes) {
    }

    record PendingApproval(ApprovalView approval, ChangeItem change) {
    }

    record RelatedIncident(UUID id, String number, String title, String statusCode, String priority,
                           Instant createdAt) {
    }

    record Impact(boolean cmdb, List<CiView> cis, List<ConfigurationItems.ImpactedService> services,
                  List<UUID> declaredServiceIds) {
    }
}
