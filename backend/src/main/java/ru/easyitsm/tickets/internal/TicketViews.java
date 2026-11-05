package ru.easyitsm.tickets.internal;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.tickets.api.TicketType;

/** Представления заявки для REST (контракт docs/api/tickets.yaml). Коды — в нижнем регистре. */
@Component
class TicketViews {

    private final TicketEngine engine;
    private final TicketAccess access;

    TicketViews(TicketEngine engine, TicketAccess access) {
        this.engine = engine;
        this.access = access;
    }

    record StatusRef(String code, String name, String category) {
    }

    record IdentityCheck(boolean required, Instant verifiedAt, UUID verifiedBy, String method) {
    }

    record TicketView(UUID id, String number, String type, String title, StatusRef status, String priority,
                      String impact, String urgency, boolean priorityManual, UUID requesterId, UUID createdForId,
                      UUID organizationId, UUID groupId, UUID assigneeId, UUID serviceId, UUID ciId, UUID categoryId,
                      UUID catalogItemId, UUID parentId, String channel, boolean confidential, UUID securityGroupId,
                      Instant dueAt, Instant slaDueAt, boolean awaitingAgent, String pendingReason,
                      Instant pendingUntil, String resolutionCode, String resolutionText, int reopenCount,
                      int escalationLevel, IdentityCheck identityCheck, Map<String, Object> custom,
                      Instant firstResponseAt, Instant resolvedAt, Instant closedAt, Instant createdAt, UUID createdBy,
                      Instant updatedAt, UUID updatedBy, long version) {
    }

    record AvailableTransition(String to, String name, String category, List<String> requiredFields) {
    }

    record Permissions(boolean edit, boolean assign, boolean comment, boolean internalNotes, boolean merge,
                       boolean setConfidential) {
    }

    record TicketDetails(@JsonUnwrapped TicketView ticket, String description, List<AvailableTransition> transitions,
                         Permissions permissions) {
    }

    /** Кэш циклов на время одного ответа: в списке много заявок одного типа. */
    final class Mapper {
        private final Map<TicketType, Workflow> workflows = new EnumMap<>(TicketType.class);

        TicketView view(TicketRow t) {
            Workflow wf = workflows.computeIfAbsent(t.type, engine::workflow);
            String name = wf.status(t.status).map(Workflow.Status::name).orElse(t.status);
            return new TicketView(t.id, t.number, t.type.code(), t.title,
                    new StatusRef(t.status, name, t.statusCategory.code()), t.priority.code(), t.impact.code(),
                    t.urgency.code(), t.priorityManual, t.requesterId, t.createdForId, t.organizationId, t.groupId,
                    t.assigneeId, t.serviceId, t.ciId, t.categoryId, t.catalogItemId, t.parentId, t.channel.code(),
                    t.confidential, t.securityGroupId, t.dueAt, t.slaDueAt, t.awaitingAgent, t.pendingReason,
                    t.pendingUntil, t.resolutionCode, t.resolutionText, t.reopenCount, t.escalationLevel,
                    new IdentityCheck(t.identityCheckRequired, t.identityVerifiedAt, t.identityVerifiedBy,
                            t.identityMethod),
                    t.custom, t.firstResponseAt, t.resolvedAt, t.closedAt, t.createdAt, t.createdBy, t.updatedAt,
                    t.updatedBy, t.version);
        }
    }

    Mapper mapper() {
        return new Mapper();
    }

    TicketView view(TicketRow t) {
        return mapper().view(t);
    }

    TicketDetails details(TicketRow t, Actor actor) {
        Workflow wf = engine.workflow(t.type);
        List<AvailableTransition> transitions = engine.available(t, actor).stream()
                .map(tr -> {
                    Workflow.Status target = wf.require(tr.to());
                    return new AvailableTransition(tr.to(), tr.name() != null ? tr.name() : target.name(),
                            target.category(), tr.requiredFields());
                })
                .toList();
        boolean active = !t.statusCategory.isFinal();
        boolean support = actor.isSupport();
        var perms = new Permissions(support && active, support && active,
                active && (support || access.canRead(t, actor)) && !actor.roles().equals(java.util.Set.of("AUD")),
                support, support && active, (support || actor.has("SEC")) && active);
        return new TicketDetails(view(t), t.description, transitions, perms);
    }
}
