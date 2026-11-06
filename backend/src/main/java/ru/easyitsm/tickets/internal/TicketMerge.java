package ru.easyitsm.tickets.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.events.TicketMerged;

/**
 * Слияние дубликатов (TCK-13): дубликаты закрываются с кодом duplicate и связью duplicate_of,
 * их заявители и наблюдатели становятся наблюдателями основной заявки, история дубликатов сохраняется.
 */
@Service
class TicketMerge {

    private final TicketService tickets;
    private final TicketEngine engine;
    private final TicketAccess access;
    private final Links links;
    private final Watchers watchers;
    private final Feed feed;
    private final TicketViews views;
    private final ApplicationEventPublisher events;

    TicketMerge(TicketService tickets, TicketEngine engine, TicketAccess access, Links links, Watchers watchers,
                Feed feed, TicketViews views, ApplicationEventPublisher events) {
        this.tickets = tickets;
        this.engine = engine;
        this.access = access;
        this.links = links;
        this.watchers = watchers;
        this.feed = feed;
        this.views = views;
        this.events = events;
    }

    record SourceResult(UUID id, String number, boolean ok, String code) {
    }

    record Result(boolean preview, TicketViews.TicketView target, List<SourceResult> sources, List<UUID> watchersAdded) {
    }

    @Transactional
    Result merge(String targetRef, List<String> sourceRefs, boolean preview, Actor actor) {
        TicketRow target = access.requireWork(tickets.resolve(targetRef, true), actor);
        TicketService.requireActive(target);

        List<SourceResult> results = new ArrayList<>();
        List<TicketRow> sources = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (String ref : sourceRefs) {
            TicketRow s;
            try {
                s = access.requireRead(tickets.resolve(ref, true), actor);
            } catch (ApiException e) {
                results.add(new SourceResult(TicketService.parseUuid(ref), ref, false, "not_found"));
                continue;
            }
            if (!access.canWork(s, actor)) {
                results.add(new SourceResult(s.id, s.number, false, "forbidden"));
                continue;
            }
            String code = null;
            if (s.id.equals(target.id)) {
                code = "same_ticket";
            } else if (s.statusCategory.isFinal()) {
                code = "already_closed";
            } else if (!seen.add(s.id)) {
                code = "duplicate_source";
            }
            results.add(new SourceResult(s.id, s.number, code == null, code));
            if (code == null) {
                sources.add(s);
            }
        }

        Set<UUID> existing = new java.util.HashSet<>(watchers.userIds(target.id));
        Set<UUID> toAdd = new LinkedHashSet<>();
        for (TicketRow s : sources) {
            List<UUID> people = new ArrayList<>();
            people.add(s.requesterId);
            if (s.createdForId != null) {
                people.add(s.createdForId);
            }
            people.addAll(watchers.userIds(s.id));
            for (UUID u : people) {
                if (!target.isRequesterSide(u) && !existing.contains(u)) {
                    toAdd.add(u);
                }
            }
        }

        boolean valid = results.stream().allMatch(SourceResult::ok) && !sources.isEmpty();
        if (preview) {
            return new Result(true, views.view(target), results, List.copyOf(toAdd));
        }
        if (!valid) {
            throw TicketProblem.clash("merge_invalid", "Не все заявки можно объединить").with("sources", results);
        }

        for (TicketRow before : sources) {
            TicketRow s = before.copy();
            ChangeContext ctx = new ChangeContext(actor).trigger("merge");
            Workflow wf = engine.workflow(s.type);
            Workflow.Status closed = wf.firstOf(StatusCategory.CLOSED)
                    .orElseThrow(() -> TicketProblem.clash("merge_invalid", "В цикле нет статуса «Закрыта»"));
            s.status = closed.code();
            s.statusCategory = StatusCategory.CLOSED;
            s.closedAt = ctx.now;
            s.pendingReason = null;
            s.pendingUntil = null;
            s.awaitingAgent = false;
            s.resolutionCode = "duplicate";
            s.resolutionText = "Объединена с " + target.number;
            ctx.reasonCode = "duplicate";
            ctx.comment = "Заявка объединена с " + target.number;
            engine.commit(before, s, ctx);
            try {
                links.add(s.id, target.id, "duplicate_of", actor.userId());
            } catch (TicketProblem e) {
                if (!"link_exists".equals(e.code())) {
                    throw e;
                }
            }
        }
        for (UUID u : toAdd) {
            watchers.add(target, u, "merge", actor);
        }
        List<UUID> ids = sources.stream().map(s -> s.id).toList();
        var now = Sql.now();
        feed.add(target.id, "system", "public", actor.userId(), false, "",
                Map.of("event", "merged", "sources", sources.stream().map(s -> s.number).toList()), List.of(), now);
        events.publishEvent(new TicketMerged(target.tenantId, now, actor.userId(), target.snapshot(), ids));
        return new Result(false, views.view(target), results, List.copyOf(toAdd));
    }
}
