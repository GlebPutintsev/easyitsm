package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketType;

/** Списки и поиск заявок с учётом прав, курсорная постраничность (API-03). */
@Component
class TicketSearch {

    static final Set<String> SORTS = Set.of("-created", "created", "-updated", "priority", "due");

    private final TicketStore store;
    private final TicketAccess access;

    TicketSearch(TicketStore store, TicketAccess access) {
        this.store = store;
        this.access = access;
    }

    record Filter(String scope, String state, String type, String status, String statusCategory, String priority,
                  String groupId, String assigneeId, UUID requesterId, UUID serviceId, UUID categoryId, String q,
                  Instant createdFrom, Instant createdTo, Instant updatedFrom, Instant dueBefore,
                  Boolean awaitingAgent, List<String> custom) {
    }

    @Transactional(readOnly = true)
    CursorPage<TicketRow> page(Filter f, String sort, PageParams page, Actor actor) {
        String s = sort == null ? "-created" : sort;
        if (!SORTS.contains(s)) {
            throw ApiException.badRequest("invalid_sort", "Сортировка: " + String.join(", ", SORTS));
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("where ").append(conditions(f, actor, p));
        KeysetCursor after = page.keyset();
        if (after != null) {
            p.put("cId", after.id());
            switch (s) {
                case "-created" -> where.append(" and (t.created_at, t.id) < (cast(:cv as timestamptz), :cId)");
                case "created" -> where.append(" and (t.created_at, t.id) > (cast(:cv as timestamptz), :cId)");
                case "-updated" -> where.append(" and (t.updated_at, t.id) < (cast(:cv as timestamptz), :cId)");
                case "due" -> where.append(
                        " and (coalesce(t.sla_due_at, 'infinity'::timestamptz), t.id) > (cast(:cv as timestamptz), :cId)");
                default -> {
                    String[] parts = after.sortValue().split("\\|", 2);
                    p.put("cp", parts[0]);
                    where.append(" and (t.priority, t.created_at, t.id) > (:cp, cast(:cv as timestamptz), :cId)");
                    p.put("cv", parts.length > 1 ? parts[1] : null);
                }
            }
            p.putIfAbsent("cv", after.sortValue());
        }
        String order = switch (s) {
            case "-created" -> " order by t.created_at desc, t.id desc";
            case "created" -> " order by t.created_at, t.id";
            case "-updated" -> " order by t.updated_at desc, t.id desc";
            case "due" -> " order by coalesce(t.sla_due_at, 'infinity'::timestamptz), t.id";
            default -> " order by t.priority, t.created_at, t.id";
        };
        p.put("limit", page.limit() + 1);
        List<TicketRow> rows = store.query(where + order + " limit :limit", p);
        return CursorPage.of(rows, page.limit(), t -> new KeysetCursor(switch (s) {
            case "-created", "created" -> t.createdAt.toString();
            case "-updated" -> t.updatedAt.toString();
            case "due" -> t.slaDueAt == null ? "infinity" : t.slaDueAt.toString();
            default -> t.priority.code() + "|" + t.createdAt;
        }, t.id));
    }

    @Transactional(readOnly = true)
    long count(Filter f, Actor actor) {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = "select count(*) from ticket t where " + conditions(f, actor, p);
        return store.jdbc().sql(sql).params(p).query(Long.class).single();
    }

    /** Похожие открытые заявки по теме и описанию (INC-05). */
    @Transactional(readOnly = true)
    List<TicketRow> similar(String q, TicketType type, int limit, Actor actor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("q", q);
        p.put("type", type.code());
        p.put("limit", limit);
        return store.query("where t.type = :type and t.status_category not in ('closed', 'cancelled')"
                + " and t.search_vector @@ plainto_tsquery('russian', :q) and " + access.filter(actor, p)
                + " order by ts_rank(t.search_vector, plainto_tsquery('russian', :q)) desc, t.created_at desc"
                + " limit :limit", p);
    }

    private String conditions(Filter f, Actor actor, Map<String, Object> p) {
        StringBuilder w = new StringBuilder(access.filter(actor, p));
        String scope = f.scope() == null ? "all" : f.scope();
        switch (scope) {
            case "all" -> {
            }
            case "mine" -> {
                p.put("me", actor.userId());
                w.append(" and (t.requester_id = :me or t.created_for_id = :me or exists (select 1 from ticket_watcher mw"
                        + " where mw.ticket_id = t.id and mw.user_id = :me))");
            }
            case "assigned_to_me" -> {
                p.put("me", actor.userId());
                w.append(" and t.assignee_id = :me");
            }
            case "unassigned" -> w.append(" and t.assignee_id is null");
            case "watching" -> {
                p.put("me", actor.userId());
                w.append(" and exists (select 1 from ticket_watcher mw where mw.ticket_id = t.id and mw.user_id = :me)");
            }
            default -> throw ApiException.badRequest("invalid_scope", "Неизвестный scope");
        }
        if (f.state() != null) {
            List<String> cats = switch (f.state()) {
                case "active" -> List.of("draft", "new", "approval", "assigned", "in_progress");
                case "waiting" -> List.of("pending", "resolved");
                case "closed" -> List.of("closed", "cancelled");
                default -> throw ApiException.badRequest("invalid_state", "state: active, waiting или closed");
            };
            p.put("stateCats", Sql.array(cats));
            w.append(" and t.status_category = any(cast(:stateCats as text[]))");
        }
        inList(w, p, "t.type", "types", f.type(), v -> TicketType.fromCode(v).code());
        inList(w, p, "t.status", "statuses", f.status(), v -> v);
        inList(w, p, "t.status_category", "cats", f.statusCategory(), v -> StatusCategory.fromCode(v).code());
        inList(w, p, "t.priority", "prios", f.priority(), v -> Priority.fromCode(v).code());
        uuidList(w, p, "t.group_id", "groups", f.groupId());
        uuidList(w, p, "t.assignee_id", "assignees", f.assigneeId());
        if (f.requesterId() != null) {
            p.put("requester", f.requesterId());
            w.append(" and t.requester_id = :requester");
        }
        if (f.serviceId() != null) {
            p.put("service", f.serviceId());
            w.append(" and t.service_id = :service");
        }
        if (f.categoryId() != null) {
            p.put("category", f.categoryId());
            w.append(" and t.category_id = :category");
        }
        if (f.q() != null && !f.q().isBlank()) {
            p.put("q", f.q().strip());
            p.put("qNumber", f.q().strip().toUpperCase(java.util.Locale.ROOT));
            w.append(" and (t.search_vector @@ websearch_to_tsquery('russian', :q) or t.number = :qNumber)");
        }
        if (f.createdFrom() != null) {
            p.put("createdFrom", Sql.ts(f.createdFrom()));
            w.append(" and t.created_at >= :createdFrom");
        }
        if (f.createdTo() != null) {
            p.put("createdTo", Sql.ts(f.createdTo()));
            w.append(" and t.created_at < :createdTo");
        }
        if (f.updatedFrom() != null) {
            p.put("updatedFrom", Sql.ts(f.updatedFrom()));
            w.append(" and t.updated_at >= :updatedFrom");
        }
        if (f.dueBefore() != null) {
            // Ближайший из сроков: SLA или ручной due_at (least пропускает null)
            p.put("dueBefore", Sql.ts(f.dueBefore()));
            w.append(" and least(t.sla_due_at, t.due_at) < :dueBefore");
        }
        if (f.awaitingAgent() != null) {
            p.put("awaiting", f.awaitingAgent());
            w.append(" and t.awaiting_agent = :awaiting");
        }
        if (f.custom() != null) {
            int i = 0;
            for (String c : f.custom()) {
                int sep = c.indexOf(':');
                if (sep < 1 || !c.substring(0, sep).matches("^[a-z][a-z0-9_]{1,62}$")) {
                    throw ApiException.badRequest("invalid_custom_filter", "Фильтр custom: <код>:<значение>");
                }
                p.put("ck" + i, c.substring(0, sep));
                p.put("cv" + i, c.substring(sep + 1));
                w.append(" and (t.custom ->> cast(:ck").append(i).append(" as text)) = :cv").append(i);
                i++;
            }
        }
        return w.toString();
    }

    private static void inList(StringBuilder w, Map<String, Object> p, String column, String name, String csv,
                               java.util.function.UnaryOperator<String> normalize) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        List<String> values;
        try {
            values = Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isEmpty()).map(normalize).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_filter", e.getMessage());
        }
        p.put(name, Sql.array(values));
        w.append(" and ").append(column).append(" = any(cast(:").append(name).append(" as text[]))");
    }

    private static void uuidList(StringBuilder w, Map<String, Object> p, String column, String name, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        boolean none = false;
        List<UUID> ids = new java.util.ArrayList<>();
        for (String v : csv.split(",")) {
            String s = v.trim();
            if (s.equals("none")) {
                none = true;
            } else if (!s.isEmpty()) {
                UUID id = TicketService.parseUuid(s);
                if (id == null) {
                    throw ApiException.badRequest("invalid_filter", "Ожидается UUID: " + s);
                }
                ids.add(id);
            }
        }
        p.put(name, Sql.array(ids));
        w.append(" and (").append(column).append(" = any(cast(:").append(name).append(" as uuid[]))");
        w.append(none ? " or " + column + " is null)" : ")");
    }
}
