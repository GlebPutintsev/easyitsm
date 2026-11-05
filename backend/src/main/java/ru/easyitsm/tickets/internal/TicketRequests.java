package ru.easyitsm.tickets.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Тела запросов REST. Частичное изменение: null — «не менять». */
final class TicketRequests {

    private TicketRequests() {
    }

    static final String TYPE = "incident|request|problem|change|task";
    static final String LEVEL = "high|medium|low";
    static final String PRIORITY = "P1|P2|P3|P4";
    static final String SECRETS = "warn|mask|allow";

    record Create(
            @NotNull @Pattern(regexp = TYPE) String type,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 100_000) String description,
            UUID requesterId,
            UUID createdForId,
            UUID organizationId,
            @Pattern(regexp = LEVEL) String impact,
            @Pattern(regexp = LEVEL) String urgency,
            @Pattern(regexp = PRIORITY) String priority,
            @Size(max = 2000) String priorityReason,
            UUID groupId,
            UUID assigneeId,
            UUID serviceId,
            UUID ciId,
            UUID categoryId,
            @Pattern(regexp = "portal|email|api|chat|monitoring|agent") String channel,
            Boolean confidential,
            UUID securityGroupId,
            Instant dueAt,
            Map<String, Object> custom,
            @Size(max = 50) List<UUID> watcherIds,
            @Pattern(regexp = SECRETS) String secrets) {
    }

    record Update(
            @Size(min = 1, max = 255) String title,
            @Size(max = 100_000) String description,
            @Pattern(regexp = LEVEL) String impact,
            @Pattern(regexp = LEVEL) String urgency,
            @Pattern(regexp = PRIORITY) String priority,
            @Size(max = 2000) String priorityReason,
            UUID groupId,
            UUID assigneeId,
            UUID serviceId,
            UUID ciId,
            UUID categoryId,
            UUID createdForId,
            UUID organizationId,
            Boolean confidential,
            @Size(max = 2000) String confidentialReason,
            UUID securityGroupId,
            Instant dueAt,
            Map<String, Object> custom,
            List<@Pattern(regexp = "groupId|assigneeId|serviceId|ciId|categoryId|createdForId|organizationId|dueAt|priority|securityGroupId") String> clear,
            @Pattern(regexp = SECRETS) String secrets) {
    }

    record Transition(
            @NotBlank String to,
            @Size(max = 10_000) String comment,
            @Size(max = 100) String reasonCode,
            @Size(max = 100) String resolutionCode,
            @Size(max = 10_000) String resolutionText,
            Instant pendingUntil) {
    }

    record Comment(
            @NotBlank @Size(max = 100_000) String body,
            @Pattern(regexp = "public|internal") String visibility,
            @Size(max = 50) List<UUID> mentions,
            @Pattern(regexp = SECRETS) String secrets) {
    }

    record Escalate(@NotNull @Pattern(regexp = "functional|hierarchical") String kind, UUID groupId,
                    @NotBlank @Size(max = 2000) String reason) {
    }

    record IdentityCheck(@NotNull @Pattern(regexp = "callback|manager_confirmation|in_person|mfa|other") String method,
                         @Size(max = 1000) String note) {
    }

    record TakeNext(UUID groupId) {
    }

    record Link(@NotBlank String targetId,
                @NotNull @Pattern(regexp = "parent_of|child_of|duplicate_of|duplicated_by|blocks|blocked_by|caused_by|causes|relates_to") String kind) {
    }

    record Subtask(@NotBlank @Size(max = 255) String title, @Size(max = 100_000) String description, UUID groupId,
                   UUID assigneeId, Instant dueAt) {
    }

    record Merge(@NotEmpty @Size(max = 50) List<String> sourceIds, Boolean preview) {
    }

    record Bulk(@NotEmpty @Size(max = 200) List<UUID> ticketIds,
                @NotNull @Pattern(regexp = "assign|transition|priority|watch") String action,
                Map<String, Object> params, Boolean preview) {
    }

    record SecretScan(@NotNull @Size(max = 200_000) String text) {
    }
}
