package ru.easyitsm.mim.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Запросы и ответы REST крупных инцидентов. */
final class MimDtos {

    private MimDtos() {
    }

    static final List<String> ROLES = List.of("manager", "tech_lead", "comms", "scribe");

    // ------------------------------------------------------------------ запросы

    /** Объявить крупным (MIM-01): заявка, обоснование, менеджер, услуги. */
    record Declare(@NotNull UUID ticketId, @NotBlank @Size(max = 2000) String reason, UUID managerId,
                   @Size(max = 50) List<UUID> serviceIds, @Size(max = 255) String publicTitle) {
    }

    record Reject(@NotBlank @Size(max = 2000) String reason) {
    }

    record Patch(@Size(max = 5000) String hypothesis, @Size(max = 50) List<UUID> serviceIds,
                 @Size(min = 1, max = 255) String publicTitle, Instant nextUpdateAt) {
    }

    record AssignRole(UUID userId) {
    }

    record AddEvent(@NotBlank @Size(max = 5000) String text, Instant at) {
    }

    record AddTask(@NotBlank @Size(max = 500) String title, UUID assigneeId) {
    }

    record SetTaskDone(@NotNull Boolean done) {
    }

    record LinkChildren(@Size(min = 1, max = 200) List<UUID> incidentIds) {
    }

    record Publish(@NotBlank String audience, @NotBlank @Size(max = 255) String title,
                   @NotBlank @Size(max = 10000) String body, Boolean statusPage, Instant nextUpdateAt) {

        boolean onStatusPage() {
            return Boolean.TRUE.equals(statusPage);
        }
    }

    record Render(@NotNull UUID templateId) {
    }

    /** Оповестить дежурных (MIM-03): сохранённая цепочка или группы по порядку. */
    record StartPage(UUID chainId, @Valid @Size(max = 10) List<Step> steps, String channel) {
    }

    record Step(UUID groupId, UUID userId, @Min(1) @Max(240) int waitMinutes) {
    }

    record Restore(Instant at, @Size(max = 2000) String comment) {
    }

    record Close(Boolean createProblem, @Size(max = 255) String problemTitle) {
    }

    record SavePir(@NotNull @Size(max = 100000) String pir) {
    }

    record SaveTemplate(@NotBlank @Size(max = 255) String name, @NotBlank String audience,
                        @NotBlank @Size(max = 255) String title, @NotBlank @Size(max = 10000) String body,
                        Integer position, Boolean active) {
    }

    record SaveChain(@NotBlank @Size(max = 255) String name, @Valid @NotNull @Size(min = 1, max = 10) List<Step> steps,
                     Boolean active) {
    }

    // ------------------------------------------------------------------ ответы

    record TicketRef(UUID id, String number, String title, String statusCode, String statusCategory,
                     String priority, boolean visible) {
    }

    record ServiceRef(UUID id, String name) {
    }

    record RoleView(String role, UUID userId) {
    }

    record EventView(UUID id, String kind, Instant at, String text, UUID actorId, Map<String, Object> meta) {
    }

    record TaskView(UUID id, String title, UUID assigneeId, boolean done, Instant doneAt, Instant createdAt) {
    }

    record UpdateView(UUID id, String audience, String title, String body, boolean statusPage, Instant nextUpdateAt,
                      Instant publishedAt, UUID publishedBy) {
    }

    record TargetView(int step, UUID userId, Instant notifiedAt, Instant ackedAt) {
    }

    record PageView(UUID id, String name, String channel, int step, int steps, String state, Instant nextStepAt,
                    UUID ackedBy, Instant ackedAt, Instant createdAt, List<TargetView> targets) {
    }

    record Permissions(boolean manage, boolean approve, boolean close) {
    }

    record MajorIncidentView(UUID id, TicketRef ticket, String state, String publicTitle, String reason,
                             String hypothesis, List<ServiceRef> services, UUID declaredBy, Instant declaredAt,
                             UUID approvedBy, Instant approvedAt, Instant restoredAt, Instant closedAt,
                             Instant nextUpdateAt, List<RoleView> roles, List<EventView> timeline,
                             List<TaskView> tasks, List<UpdateView> updates, List<PageView> pages,
                             List<TicketRef> children, String pir, TicketRef problem, Permissions permissions,
                             long version) {
    }

    record MajorIncidentRow(UUID id, TicketRef ticket, String state, String publicTitle, List<ServiceRef> services,
                            UUID managerId, Instant declaredAt, Instant restoredAt, Instant closedAt,
                            Instant nextUpdateAt, int children) {
    }

    record Rendered(String title, String body, String audience) {
    }

    record TemplateView(UUID id, String name, String audience, String title, String body, int position,
                        boolean active, long version) {
    }

    record ChainView(UUID id, String name, List<Step> steps, boolean active, long version) {
    }

    record StatusIncident(UUID id, String title, String state, List<ServiceRef> services, Instant declaredAt,
                          Instant restoredAt, Instant nextUpdateAt, UpdateView lastUpdate) {
    }

    record StatusAnnouncement(UUID id, String kind, String title, String body, Instant startsAt, Instant endsAt) {
    }

    record StatusPage(List<StatusIncident> majorIncidents, List<StatusAnnouncement> announcements,
                      Instant generatedAt) {
    }
}
