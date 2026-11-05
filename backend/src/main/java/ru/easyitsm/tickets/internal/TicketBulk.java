package ru.easyitsm.tickets.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;

/**
 * Массовые операции с предпросмотром и отменой в течение 10 секунд (TCK-12).
 * Каждая заявка обрабатывается в своей транзакции: ошибка одной не откатывает остальные.
 * Предпросмотр выполняет то же действие и откатывает транзакцию, поэтому показывает ровно то, что случится.
 */
@Service
class TicketBulk {

    static final Duration UNDO_WINDOW = Duration.ofSeconds(10);

    private final TicketService tickets;
    private final TicketStore store;
    private final TicketEngine engine;
    private final Watchers watchers;
    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate tx;

    TicketBulk(TicketService tickets, TicketStore store, TicketEngine engine, Watchers watchers, JdbcClient jdbc,
               Json json, PlatformTransactionManager txm) {
        this.tickets = tickets;
        this.store = store;
        this.engine = engine;
        this.watchers = watchers;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record Item(UUID ticketId, String number, boolean ok, String code, String detail, List<Feed.Change> changes) {
    }

    record Result(boolean preview, UUID operationId, Instant undoUntil, List<Item> items) {
    }

    /** Что вернуть при отмене: значения полей до операции и версия после неё. */
    record Snapshot(UUID ticketId, long versionAfter, Map<String, Object> before, boolean watcherAdded) {
    }

    Result run(TicketRequests.Bulk req, Actor actor) {
        if (!actor.isSupport()) {
            throw ApiException.forbidden();
        }
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        boolean preview = Boolean.TRUE.equals(req.preview());
        List<Item> items = new ArrayList<>();
        List<Snapshot> snapshots = new ArrayList<>();
        for (UUID id : req.ticketIds().stream().distinct().toList()) {
            try {
                Outcome o = tx.execute(status -> {
                    Outcome out = apply(id, req.action(), params, actor);
                    if (preview) {
                        status.setRollbackOnly();
                    }
                    return out;
                });
                items.add(new Item(id, o.number(), true, null, null, o.changes()));
                if (!preview && (!o.changes().isEmpty() || o.watcherAdded())) {
                    snapshots.add(new Snapshot(id, o.versionAfter(), o.before(), o.watcherAdded()));
                }
            } catch (ApiException e) {
                items.add(new Item(id, numberOf(id), false, e.code(), e.getMessage(), List.of()));
            }
        }
        if (preview) {
            return new Result(true, null, null, items);
        }
        UUID opId = Ids.newId();
        Instant now = Sql.now();
        Instant undoUntil = now.plus(UNDO_WINDOW);
        tx.executeWithoutResult(s -> jdbc.sql("""
                insert into ticket_bulk_operation (id, tenant_id, actor_id, action, params, snapshot, undo_until,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :actor, :action, cast(:params as jsonb), cast(:snapshot as jsonb), :until,
                    :now, :actor, :now, :actor, 0)""")
                .param("id", opId).param("tenant", TenantContext.require()).param("actor", actor.userId())
                .param("action", req.action()).param("params", json.write(params))
                .param("snapshot", json.write(snapshots)).param("until", Sql.ts(undoUntil)).param("now", Sql.ts(now))
                .update());
        return new Result(false, opId, undoUntil, items);
    }

    private record Outcome(String number, List<Feed.Change> changes, long versionAfter, Map<String, Object> before,
                           boolean watcherAdded) {
    }

    private Outcome apply(UUID id, String action, Map<String, Object> p, Actor actor) {
        TicketRow current = tickets.read(id.toString(), actor);
        String etag = ETags.of(current.version);
        Map<String, Object> before = snapshotOf(current);
        boolean watcherAdded = false;
        TicketRow after = switch (action) {
            case "assign" -> tickets.update(id.toString(), etag, new TicketRequests.Update(null, null, null, null, null,
                    null, uuid(p, "groupId"), uuid(p, "assigneeId"), null, null, null, null, null, null, null, null,
                    null, null, p.containsKey("assigneeId") && p.get("assigneeId") == null ? List.of("assigneeId")
                    : null, "allow"), actor);
            case "transition" -> tickets.transition(id.toString(), etag, new TicketRequests.Transition(
                    str(p, "to"), str(p, "comment"), str(p, "reasonCode"), str(p, "resolutionCode"),
                    str(p, "resolutionText"), p.get("pendingUntil") == null ? null
                    : Instant.parse(p.get("pendingUntil").toString())), actor);
            case "priority" -> {
                validate(p);
                yield tickets.update(id.toString(), etag, new TicketRequests.Update(null, null, str(p, "impact"),
                        str(p, "urgency"), str(p, "priority"), str(p, "reason"), null, null, null, null, null, null,
                        null, null, null, null, null, null, null, "allow"), actor);
            }
            case "watch" -> {
                UUID user = uuid(p, "userId");
                if (user == null) {
                    throw TicketProblem.fieldRequired("userId", "Укажите пользователя");
                }
                watcherAdded = watchers.add(current, user, "added", actor);
                before.put("watcher", user.toString());
                yield current;
            }
            default -> throw ApiException.badRequest("invalid_action", "Неизвестное действие");
        };
        List<Feed.Change> changes = diffForPreview(current, after);
        if (watcherAdded) {
            changes = List.of(new Feed.Change("watcher", null, p.get("userId"), actor.userId(), null, Sql.now()));
        }
        return new Outcome(current.number, changes, after.version, before, watcherAdded);
    }

    private static void validate(Map<String, Object> p) {
        try {
            Level.fromCode(str(p, "impact"));
            Level.fromCode(str(p, "urgency"));
            Priority.fromCode(str(p, "priority"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_params", e.getMessage());
        }
    }

    /** Отмена операции её автором, пока не истёк срок. Изменённые с тех пор заявки пропускаются. */
    Result undo(UUID operationId, Actor actor) {
        record Op(UUID actor, String snapshot, Instant undoUntil, Instant undoneAt) {
        }
        Op op = jdbc.sql("""
                select actor_id, snapshot::text as snapshot, undo_until, undone_at from ticket_bulk_operation
                 where id = :id""")
                .param("id", operationId)
                .query((rs, n) -> new Op(Sql.uuid(rs, "actor_id"), rs.getString("snapshot"), Sql.instant(rs, "undo_until"),
                        Sql.instant(rs, "undone_at")))
                .optional()
                .filter(o -> o.actor().equals(actor.userId()))
                .orElseThrow(() -> ApiException.notFound("Операция"));
        if (op.undoneAt() != null) {
            throw TicketProblem.clash("undo_expired", "Операция уже отменена");
        }
        if (Sql.now().isAfter(op.undoUntil())) {
            throw TicketProblem.clash("undo_expired", "Срок отмены истёк");
        }
        int marked = jdbc.sql("update ticket_bulk_operation set undone_at = now(), version = version + 1"
                        + " where id = :id and undone_at is null")
                .param("id", operationId).update();
        if (marked == 0) {
            throw TicketProblem.clash("undo_expired", "Операция уже отменена");
        }
        List<Item> items = new ArrayList<>();
        for (Object raw : json.readList(op.snapshot())) {
            Snapshot s = json.convert(raw, Snapshot.class);
            try {
                List<Feed.Change> changes = tx.execute(st -> restore(s, actor));
                items.add(new Item(s.ticketId(), numberOf(s.ticketId()), true, null, null, changes));
            } catch (ApiException e) {
                items.add(new Item(s.ticketId(), numberOf(s.ticketId()), false, e.code(), e.getMessage(), List.of()));
            }
        }
        return new Result(false, operationId, op.undoUntil(), items);
    }

    private List<Feed.Change> restore(Snapshot s, Actor actor) {
        TicketRow current = store.lock(s.ticketId()).orElseThrow(() -> ApiException.notFound("Заявка"));
        if (s.watcherAdded()) {
            Object user = s.before().get("watcher");
            watchers.remove(current.id, UUID.fromString(user.toString()));
            return List.of();
        }
        if (current.version != s.versionAfter()) {
            throw TicketProblem.clash("changed_since", "Заявку изменили после массовой операции");
        }
        TicketRow t = current.copy();
        Map<String, Object> b = s.before();
        t.status = (String) b.get("status");
        t.statusCategory = StatusCategory.fromCode((String) b.get("statusCategory"));
        t.priority = Priority.fromCode((String) b.get("priority"));
        t.impact = Level.fromCode((String) b.get("impact"));
        t.urgency = Level.fromCode((String) b.get("urgency"));
        t.priorityManual = Boolean.TRUE.equals(b.get("priorityManual"));
        t.groupId = uuid(b, "groupId");
        t.assigneeId = uuid(b, "assigneeId");
        t.pendingReason = (String) b.get("pendingReason");
        t.pendingUntil = instant(b, "pendingUntil");
        t.resolutionCode = (String) b.get("resolutionCode");
        t.resolutionText = (String) b.get("resolutionText");
        t.resolvedAt = instant(b, "resolvedAt");
        t.closedAt = instant(b, "closedAt");
        t.reopenCount = ((Number) b.get("reopenCount")).intValue();
        ChangeContext ctx = new ChangeContext(actor).trigger("undo");
        ctx.reason = "Отмена массовой операции";
        return engine.commit(current, t, ctx).changes();
    }

    private static Map<String, Object> snapshotOf(TicketRow t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", t.status);
        m.put("statusCategory", t.statusCategory.code());
        m.put("priority", t.priority.code());
        m.put("impact", t.impact.code());
        m.put("urgency", t.urgency.code());
        m.put("priorityManual", t.priorityManual);
        m.put("groupId", str(t.groupId));
        m.put("assigneeId", str(t.assigneeId));
        m.put("pendingReason", t.pendingReason);
        m.put("pendingUntil", str(t.pendingUntil));
        m.put("resolutionCode", t.resolutionCode);
        m.put("resolutionText", t.resolutionText);
        m.put("resolvedAt", str(t.resolvedAt));
        m.put("closedAt", str(t.closedAt));
        m.put("reopenCount", t.reopenCount);
        return m;
    }

    private static List<Feed.Change> diffForPreview(TicketRow before, TicketRow after) {
        List<Feed.Change> out = new ArrayList<>();
        Map<String, Object[]> pairs = new LinkedHashMap<>();
        pairs.put("status", new Object[]{before.status, after.status});
        pairs.put("priority", new Object[]{before.priority.code(), after.priority.code()});
        pairs.put("impact", new Object[]{before.impact.code(), after.impact.code()});
        pairs.put("urgency", new Object[]{before.urgency.code(), after.urgency.code()});
        pairs.put("groupId", new Object[]{str(before.groupId), str(after.groupId)});
        pairs.put("assigneeId", new Object[]{str(before.assigneeId), str(after.assigneeId)});
        for (var e : pairs.entrySet()) {
            if (!java.util.Objects.equals(e.getValue()[0], e.getValue()[1])) {
                out.add(new Feed.Change(e.getKey(), e.getValue()[0], e.getValue()[1], after.updatedBy, null,
                        after.updatedAt));
            }
        }
        return out;
    }

    private String numberOf(UUID id) {
        return store.find(id).map(t -> t.number).orElse(null);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return null;
        }
        UUID id = TicketService.parseUuid(v.toString());
        if (id == null) {
            throw ApiException.badRequest("invalid_params", key + ": ожидается UUID");
        }
        return id;
    }

    private static Instant instant(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : Instant.parse(v.toString());
    }
}
