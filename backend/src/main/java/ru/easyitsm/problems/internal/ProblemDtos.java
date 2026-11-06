package ru.easyitsm.problems.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Запросы и ответы REST проблем и известных ошибок. */
final class ProblemDtos {

    private ProblemDtos() {
    }

    /** Категории диаграммы Исикавы для ИТ (PRB-02). */
    static final List<String> ISHIKAWA = List.of("people", "process", "technology", "environment", "suppliers",
            "measurement");

    // ------------------------------------------------------------------ запросы

    record CreateProblem(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20000) String description,
            @Size(max = 100) List<UUID> incidentIds,
            String source,
            UUID serviceId,
            UUID ciId,
            UUID groupId,
            String impact,
            String urgency) {
    }

    record LinkIncidents(@Size(min = 1, max = 100) List<UUID> incidentIds) {
    }

    record TimelineEntry(Instant at, @Size(max = 2000) String text) {
    }

    record Why(@Size(max = 1000) String question, @Size(max = 2000) String answer) {
    }

    /** Анализ причин: хронология, «5 почему» (до 10 шагов), Исикава по категориям, заключение. */
    record Analysis(
            @Valid @Size(max = 200) List<TimelineEntry> timeline,
            @Valid @Size(max = 10) List<Why> whys,
            Map<String, List<String>> ishikawa,
            @Size(max = 20000) String conclusion) {

        static Analysis empty() {
            return new Analysis(List.of(), List.of(), Map.of(), "");
        }

        Analysis normalized() {
            return new Analysis(timeline == null ? List.of() : timeline, whys == null ? List.of() : whys,
                    ishikawa == null ? Map.of() : ishikawa, conclusion == null ? "" : conclusion);
        }
    }

    record UpdateAnalysis(
            @Size(max = 20000) String rootCause,
            @Size(max = 20000) String workaround,
            @Valid Analysis analysis,
            Boolean closeIncidents) {
    }

    record AcceptRisk(@NotBlank @Size(max = 2000) String reason, Instant reviewAt) {
    }

    record SaveKnownError(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20000) String symptoms,
            @Size(max = 20000) String workaround,
            UUID serviceId,
            UUID ciId,
            UUID categoryId) {
    }

    record CreateChange(@NotBlank @Size(max = 255) String title, @Size(max = 20000) String description) {
    }

    record ResolveIncidents(@Size(max = 2000) String comment) {
    }

    // ------------------------------------------------------------------ ответы

    record LinkedTicket(UUID id, String number, String title, String statusCode, String statusCategory,
                        String priority, Instant createdAt, boolean visible) {
    }

    record Risk(UUID acceptedBy, Instant acceptedAt, Instant reviewAt, String reason) {
    }

    record KnownErrorView(UUID id, UUID problemId, String problemNumber, String title, String symptoms,
                          String workaround, UUID serviceId, UUID ciId, UUID categoryId, boolean published,
                          Instant publishedAt, boolean fixed, Instant updatedAt, long version) {
    }

    record Permissions(boolean edit, boolean publishKnownError, boolean createChange, boolean resolveIncidents) {
    }

    record ProblemView(
            UUID id,
            String number,
            String title,
            String statusCode,
            String statusCategory,
            String priority,
            UUID groupId,
            UUID assigneeId,
            UUID serviceId,
            UUID ciId,
            UUID categoryId,
            Instant createdAt,
            Instant resolvedAt,
            String source,
            String rootCause,
            String workaround,
            Analysis analysis,
            boolean closeIncidents,
            Risk risk,
            KnownErrorView knownError,
            List<LinkedTicket> incidents,
            List<LinkedTicket> changes,
            Permissions permissions,
            long version) {
    }

    record ResolveResult(List<String> resolved, List<Skipped> skipped) {
    }

    record Skipped(String number, String reason) {
    }

    record ProblemRefView(UUID id, String number) {
    }

    record RecurringCluster(String dimension, UUID key, String name, long count, long open, Instant firstAt,
                            Instant lastAt, List<UUID> incidentIds, List<LinkedTicket> sample,
                            List<ProblemRefView> problems) {
    }
}
