package ru.easyitsm.catalog.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Маршрут согласования одной заявки со снимком шагов на момент заказа. */
@Table("approval_request")
class ApprovalRequest extends TenantEntity {

    private UUID ticketId;
    private String ticketNumber;
    private String title;
    private UUID itemId;
    private UUID flowId;
    private UUID requesterId;
    private UUID createdForId;
    private String steps;
    private int currentStep;
    private String state;
    private Instant decidedAt;

    protected ApprovalRequest() {
    }

    ApprovalRequest(UUID ticketId, String ticketNumber, String title, UUID itemId, UUID flowId, UUID requesterId,
                    UUID createdForId, String steps) {
        this.ticketId = ticketId;
        this.ticketNumber = ticketNumber;
        this.title = title;
        this.itemId = itemId;
        this.flowId = flowId;
        this.requesterId = requesterId;
        this.createdForId = createdForId;
        this.steps = steps;
        this.state = "pending";
    }

    void moveTo(int step) {
        this.currentStep = step;
    }

    void finish(String state) {
        this.state = state;
        this.decidedAt = Instant.now();
    }

    boolean pending() {
        return "pending".equals(state);
    }

    UUID ticketId() {
        return ticketId;
    }

    String ticketNumber() {
        return ticketNumber;
    }

    String title() {
        return title;
    }

    UUID requesterId() {
        return requesterId;
    }

    UUID createdForId() {
        return createdForId;
    }

    String steps() {
        return steps;
    }

    int currentStep() {
        return currentStep;
    }

    String state() {
        return state;
    }

    Instant decidedAt() {
        return decidedAt;
    }
}
