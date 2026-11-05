package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.TicketType;

/** Связи, подзадачи и наблюдатели (TCK-05, TCK-06, TCK-10). */
@Service
class TicketRelations {

    private final TicketService tickets;
    private final TicketStore store;
    private final TicketAccess access;
    private final Links links;
    private final Watchers watchers;
    private final Feed feed;
    private final TicketViews views;

    TicketRelations(TicketService tickets, TicketStore store, TicketAccess access, Links links, Watchers watchers,
                    Feed feed, TicketViews views) {
        this.tickets = tickets;
        this.store = store;
        this.access = access;
        this.links = links;
        this.watchers = watchers;
        this.feed = feed;
        this.views = views;
    }

    record LinkedTicket(UUID id, String number, String title, TicketViews.StatusRef status) {
    }

    record LinkView(UUID id, String kind, LinkedTicket ticket, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    List<LinkView> links(String ref, Actor actor) {
        TicketRow t = tickets.read(ref, actor);
        var mapper = views.mapper();
        return links.list(t.id).stream().map(l -> {
            TicketRow other = store.find(l.otherId()).orElse(null);
            LinkedTicket lt;
            if (other == null) {
                lt = new LinkedTicket(l.otherId(), null, null, null);
            } else if (access.canRead(other, actor)) {
                lt = new LinkedTicket(other.id, other.number, other.title, mapper.view(other).status());
            } else {
                lt = new LinkedTicket(other.id, other.number, null, null); // нет прав: только номер
            }
            return new LinkView(l.id(), l.kind(), lt, l.createdAt());
        }).toList();
    }

    @Transactional
    LinkView link(String ref, TicketRequests.Link req, Actor actor) {
        access.requireSupport(actor);
        TicketRow t = tickets.read(ref, actor);
        TicketRow other = tickets.read(req.targetId(), actor);
        UUID id = links.add(t.id, other.id, req.kind(), actor.userId());
        Instant now = Sql.now();
        Map<String, Object> meta = Map.of("event", "linked", "kind", req.kind(), "ticketId", other.id.toString(),
                "number", other.number);
        feed.add(t.id, "system", "internal", actor.userId(), false, "", meta, List.of(), now);
        feed.add(other.id, "system", "internal", actor.userId(), false, "",
                Map.of("event", "linked", "kind", links.list(other.id).stream().filter(l -> l.id().equals(id))
                        .findFirst().orElseThrow().kind(), "ticketId", t.id.toString(), "number", t.number),
                List.of(), now);
        return links(ref, actor).stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    void unlink(String ref, UUID linkId, Actor actor) {
        access.requireSupport(actor);
        TicketRow t = tickets.read(ref, actor);
        UUID other = links.remove(t.id, linkId);
        Instant now = Sql.now();
        feed.add(t.id, "system", "internal", actor.userId(), false, "",
                Map.of("event", "unlinked", "ticketId", other.toString()), List.of(), now);
        feed.add(other, "system", "internal", actor.userId(), false, "",
                Map.of("event", "unlinked", "ticketId", t.id.toString()), List.of(), now);
    }

    @Transactional(readOnly = true)
    List<TicketRow> subtasks(String ref, Actor actor) {
        TicketRow t = tickets.read(ref, actor);
        return store.query("where t.parent_id = :p order by t.created_at, t.id", Map.of("p", t.id)).stream()
                .filter(s -> access.canRead(s, actor))
                .toList();
    }

    @Transactional
    TicketRow addSubtask(String ref, TicketRequests.Subtask req, Actor actor) {
        access.requireSupport(actor);
        TicketRow parent = tickets.read(ref, actor);
        TicketService.requireActive(parent);
        String description = tickets.guard(req.description(), "mask");
        var draft = new TicketService.Draft(TicketType.TASK, tickets.guard(req.title(), "mask"), description,
                actor.userId(), null, parent.organizationId, Channel.AGENT, parent.impact, parent.urgency, null, null,
                req.groupId() != null ? req.groupId() : parent.groupId, req.assigneeId(), parent.serviceId,
                parent.ciId, null, null, parent.id, parent.confidential, parent.securityGroupId, req.dueAt(), Map.of(),
                List.of(), false);
        TicketRow sub = tickets.create(draft, actor);
        feed.add(parent.id, "system", "internal", actor.userId(), false, "",
                Map.of("event", "subtask_created", "ticketId", sub.id.toString(), "number", sub.number), List.of(),
                sub.createdAt);
        return sub;
    }

    @Transactional(readOnly = true)
    List<Watchers.Watcher> watchers(String ref, Actor actor) {
        return watchers.list(tickets.read(ref, actor).id);
    }

    /** Себя подписывает любой, кто видит заявку; других — поддержка. */
    @Transactional
    void watch(String ref, String userRef, Actor actor) {
        TicketRow t = tickets.read(ref, actor);
        UUID user = user(userRef, actor);
        if (!actor.is(user)) {
            access.requireSupport(actor);
        }
        watchers.add(t, user, actor.is(user) ? "self" : "added", actor);
    }

    @Transactional
    void unwatch(String ref, String userRef, Actor actor) {
        TicketRow t = tickets.read(ref, actor);
        UUID user = user(userRef, actor);
        if (!actor.is(user)) {
            access.requireSupport(actor);
        }
        watchers.remove(t.id, user);
    }

    private static UUID user(String ref, Actor actor) {
        if ("me".equals(ref)) {
            return actor.userId();
        }
        UUID id = TicketService.parseUuid(ref);
        if (id == null) {
            throw ApiException.badRequest("invalid_user", "Ожидается UUID пользователя или me");
        }
        return id;
    }
}
