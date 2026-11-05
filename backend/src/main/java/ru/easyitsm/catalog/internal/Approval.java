package ru.easyitsm.catalog.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Решение одного согласующего на шаге маршрута. */
@Table("approval")
class Approval extends TenantEntity {

    private UUID requestId;
    private UUID ticketId;
    private int stepIndex;
    private String stepName;
    private UUID approverId;
    private UUID delegateFrom;
    private String decision;
    private String comment;
    private Instant infoRequestedAt;
    private Instant dueAt;
    private Instant decidedAt;
    private String authMethod;

    protected Approval() {
    }

    Approval(UUID requestId, UUID ticketId, int stepIndex, String stepName, UUID approverId, UUID delegateFrom,
             Instant dueAt) {
        this.requestId = requestId;
        this.ticketId = ticketId;
        this.stepIndex = stepIndex;
        this.stepName = stepName;
        this.approverId = approverId;
        this.delegateFrom = delegateFrom;
        this.dueAt = dueAt;
        this.decision = "pending";
    }

    void decide(String decision, String comment, String authMethod) {
        this.decision = decision;
        this.comment = comment;
        this.authMethod = authMethod;
        this.decidedAt = Instant.now();
    }

    void close(String decision) {
        this.decision = decision;
        this.decidedAt = Instant.now();
    }

    void requestInfo(String question) {
        this.comment = question;
        this.infoRequestedAt = Instant.now();
    }

    boolean pending() {
        return "pending".equals(decision);
    }

    UUID requestId() {
        return requestId;
    }

    UUID ticketId() {
        return ticketId;
    }

    int stepIndex() {
        return stepIndex;
    }

    String stepName() {
        return stepName;
    }

    UUID approverId() {
        return approverId;
    }

    UUID delegateFrom() {
        return delegateFrom;
    }

    String decision() {
        return decision;
    }

    String comment() {
        return comment;
    }

    Instant infoRequestedAt() {
        return infoRequestedAt;
    }

    Instant dueAt() {
        return dueAt;
    }

    Instant decidedAt() {
        return decidedAt;
    }
}
