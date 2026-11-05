package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Строка таблицы {@code ticket}. Изменяемый объект внутри одной транзакции: сервис копирует его
 * ({@link #copy()}), меняет и передаёт обе версии в {@link TicketStore#update}, а история и события
 * считаются из разницы.
 */
final class TicketRow {

    UUID id;
    UUID tenantId;
    String number;
    TicketType type;
    String title;
    String description = "";
    String status;
    StatusCategory statusCategory;
    Priority priority;
    Level impact;
    Level urgency;
    boolean priorityManual;
    UUID requesterId;
    UUID createdForId;
    UUID organizationId;
    UUID groupId;
    UUID assigneeId;
    UUID serviceId;
    UUID ciId;
    UUID categoryId;
    UUID catalogItemId;
    UUID parentId;
    Channel channel;
    boolean confidential;
    UUID securityGroupId;
    Instant dueAt;
    String pendingReason;
    Instant pendingUntil;
    String resolutionCode;
    String resolutionText;
    int reopenCount;
    int escalationLevel;
    boolean identityCheckRequired;
    Instant identityVerifiedAt;
    UUID identityVerifiedBy;
    String identityMethod;
    Instant firstResponseAt;
    Instant resolvedAt;
    Instant closedAt;
    Instant slaDueAt;
    boolean awaitingAgent;
    Map<String, Object> custom = new LinkedHashMap<>();
    Instant createdAt;
    UUID createdBy;
    Instant updatedAt;
    UUID updatedBy;
    long version;

    TicketRow copy() {
        TicketRow c = new TicketRow();
        c.id = id;
        c.tenantId = tenantId;
        c.number = number;
        c.type = type;
        c.title = title;
        c.description = description;
        c.status = status;
        c.statusCategory = statusCategory;
        c.priority = priority;
        c.impact = impact;
        c.urgency = urgency;
        c.priorityManual = priorityManual;
        c.requesterId = requesterId;
        c.createdForId = createdForId;
        c.organizationId = organizationId;
        c.groupId = groupId;
        c.assigneeId = assigneeId;
        c.serviceId = serviceId;
        c.ciId = ciId;
        c.categoryId = categoryId;
        c.catalogItemId = catalogItemId;
        c.parentId = parentId;
        c.channel = channel;
        c.confidential = confidential;
        c.securityGroupId = securityGroupId;
        c.dueAt = dueAt;
        c.pendingReason = pendingReason;
        c.pendingUntil = pendingUntil;
        c.resolutionCode = resolutionCode;
        c.resolutionText = resolutionText;
        c.reopenCount = reopenCount;
        c.escalationLevel = escalationLevel;
        c.identityCheckRequired = identityCheckRequired;
        c.identityVerifiedAt = identityVerifiedAt;
        c.identityVerifiedBy = identityVerifiedBy;
        c.identityMethod = identityMethod;
        c.firstResponseAt = firstResponseAt;
        c.resolvedAt = resolvedAt;
        c.closedAt = closedAt;
        c.slaDueAt = slaDueAt;
        c.awaitingAgent = awaitingAgent;
        c.custom = new LinkedHashMap<>(custom);
        c.createdAt = createdAt;
        c.createdBy = createdBy;
        c.updatedAt = updatedAt;
        c.updatedBy = updatedBy;
        c.version = version;
        return c;
    }

    /** Участник заявки: заявитель, получатель или исполнитель. Наблюдатели проверяются отдельно. */
    boolean isParticipant(UUID user) {
        return user != null && (user.equals(requesterId) || user.equals(createdForId) || user.equals(assigneeId));
    }

    boolean isRequesterSide(UUID user) {
        return user != null && (user.equals(requesterId) || user.equals(createdForId));
    }

    TicketSnapshot snapshot() {
        return new TicketSnapshot(id, number, type, title, status, statusCategory, priority, impact, urgency,
                requesterId, createdForId, organizationId, groupId, assigneeId, serviceId, ciId, categoryId,
                catalogItemId, channel, confidential, parentId, dueAt, createdAt, firstResponseAt, resolvedAt,
                closedAt, version);
    }
}
