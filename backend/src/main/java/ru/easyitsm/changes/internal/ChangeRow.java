package ru.easyitsm.changes.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.changes.api.ChangeOutcome;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.ChangeSummary;
import ru.easyitsm.changes.api.ChangeType;
import ru.easyitsm.changes.api.RiskLevel;

/** Строка {@code change_detail}. Изменяемая: сервис правит копию и сохраняет её с проверкой версии. */
final class ChangeRow {

    UUID id;
    UUID tenantId;
    String number;
    String title;
    UUID authorId;
    UUID groupId;
    UUID assigneeId;
    boolean confidential;
    ChangeType type;
    ChangeState state;
    UUID templateId;
    UUID sourceTicketId;
    String goal = "";
    String justification = "";
    String impact = "";
    String risks = "";
    List<Map<String, Object>> impactRows = new ArrayList<>();
    String systemCriticality;
    Instant windowStart;
    Instant windowEnd;
    String timeZone = "Europe/Moscow";
    Instant prepStart;
    Integer downtimeMinutes;
    List<UUID> ciIds = List.of();
    List<UUID> serviceIds = List.of();
    Map<String, Object> riskAnswers = new LinkedHashMap<>();
    Integer riskScore;
    RiskLevel riskLevel;
    boolean riskManual;
    String riskReason;
    Instant riskAssessedAt;
    UUID riskAssessedBy;
    String conflictsAck;
    Instant submittedAt;
    Instant approvedAt;
    Instant postApprovalDueAt;
    Instant actualStart;
    Instant actualEnd;
    Instant rollbackStartedAt;
    ChangeOutcome outcome;
    String conclusion;
    Map<String, Object> review = new LinkedHashMap<>();
    Instant closedAt;
    String cancelReason;
    Map<String, Object> pipeline;
    Instant createdAt;
    UUID createdBy;
    Instant updatedAt;
    UUID updatedBy;
    long version;

    ChangeRow copy() {
        ChangeRow c = new ChangeRow();
        c.id = id;
        c.tenantId = tenantId;
        c.number = number;
        c.title = title;
        c.authorId = authorId;
        c.groupId = groupId;
        c.assigneeId = assigneeId;
        c.confidential = confidential;
        c.type = type;
        c.state = state;
        c.templateId = templateId;
        c.sourceTicketId = sourceTicketId;
        c.goal = goal;
        c.justification = justification;
        c.impact = impact;
        c.risks = risks;
        c.impactRows = new ArrayList<>(impactRows);
        c.systemCriticality = systemCriticality;
        c.windowStart = windowStart;
        c.windowEnd = windowEnd;
        c.timeZone = timeZone;
        c.prepStart = prepStart;
        c.downtimeMinutes = downtimeMinutes;
        c.ciIds = ciIds;
        c.serviceIds = serviceIds;
        c.riskAnswers = new LinkedHashMap<>(riskAnswers);
        c.riskScore = riskScore;
        c.riskLevel = riskLevel;
        c.riskManual = riskManual;
        c.riskReason = riskReason;
        c.riskAssessedAt = riskAssessedAt;
        c.riskAssessedBy = riskAssessedBy;
        c.conflictsAck = conflictsAck;
        c.submittedAt = submittedAt;
        c.approvedAt = approvedAt;
        c.postApprovalDueAt = postApprovalDueAt;
        c.actualStart = actualStart;
        c.actualEnd = actualEnd;
        c.rollbackStartedAt = rollbackStartedAt;
        c.outcome = outcome;
        c.conclusion = conclusion;
        c.review = new LinkedHashMap<>(review);
        c.closedAt = closedAt;
        c.cancelReason = cancelReason;
        c.pipeline = pipeline == null ? null : new LinkedHashMap<>(pipeline);
        c.createdAt = createdAt;
        c.createdBy = createdBy;
        c.updatedAt = updatedAt;
        c.updatedBy = updatedBy;
        c.version = version;
        return c;
    }

    ChangeSummary summary() {
        return new ChangeSummary(id, number, title, type, state, riskLevel, windowStart, windowEnd, ciIds, serviceIds,
                outcome);
    }
}
