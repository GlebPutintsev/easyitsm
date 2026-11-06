package ru.easyitsm.mim.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.mim.internal.MimDtos.EventView;
import ru.easyitsm.mim.internal.MimDtos.Step;
import ru.easyitsm.mim.internal.MimDtos.TargetView;
import ru.easyitsm.mim.internal.MimDtos.TaskView;
import ru.easyitsm.mim.internal.MimDtos.UpdateView;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Таблицы модуля крупных инцидентов. */
@Repository
class MimStore {

    record Incident(UUID id, UUID ticketId, String state, String publicTitle, String reason, String hypothesis,
                    List<UUID> serviceIds, UUID declaredBy, Instant declaredAt, UUID approvedBy, Instant approvedAt,
                    Instant restoredAt, Instant closedAt, Instant nextUpdateAt, String pir, UUID problemId,
                    long version) {

        boolean open() {
            return "active".equals(state) || "restored".equals(state);
        }
    }

    record Page(UUID id, UUID mimId, UUID chainId, String name, List<Step> steps, String channel, int step,
                String state, Instant nextStepAt, UUID ackedBy, Instant ackedAt, Instant createdAt, long version) {
    }

    record Template(UUID id, String name, String audience, String title, String body, int position, boolean active,
                    long version) {
    }

    record Chain(UUID id, String name, List<Step> steps, boolean active, long version) {
    }

    private static final TypeReference<List<Step>> STEPS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    MimStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ крупный инцидент

    private Incident incident(ResultSet rs) throws SQLException {
        return new Incident(Db.uuid(rs, "id"), Db.uuid(rs, "ticket_id"), rs.getString("state"),
                rs.getString("public_title"), rs.getString("reason"), rs.getString("hypothesis"),
                uuids(rs.getArray("service_ids")), Db.uuid(rs, "declared_by"), Db.instant(rs, "declared_at"),
                Db.uuid(rs, "approved_by"), Db.instant(rs, "approved_at"), Db.instant(rs, "restored_at"),
                Db.instant(rs, "closed_at"), Db.instant(rs, "next_update_at"), rs.getString("pir"),
                Db.uuid(rs, "problem_id"), rs.getLong("version"));
    }

    Optional<Incident> find(UUID id, boolean lock) {
        return jdbc.sql("select * from mim_incident where id = :id" + (lock ? " for update" : ""))
                .param("id", id).query((rs, n) -> incident(rs)).optional();
    }

    /** Действующий (на согласовании, объявленный, восстановленный) по заявке. */
    Optional<Incident> openForTicket(UUID ticketId) {
        return jdbc.sql("""
                select * from mim_incident where ticket_id = :t and state in ('requested', 'active', 'restored')""")
                .param("t", ticketId).query((rs, n) -> incident(rs)).optional();
    }

    Optional<Incident> latestForTicket(UUID ticketId) {
        return jdbc.sql("select * from mim_incident where ticket_id = :t order by declared_at desc limit 1")
                .param("t", ticketId).query((rs, n) -> incident(rs)).optional();
    }

    List<Incident> list(List<String> states, int limit) {
        return jdbc.sql("""
                select * from mim_incident where state = any(cast(:states as text[]))
                 order by declared_at desc limit :limit""")
                .param("states", "{" + String.join(",", states) + "}").param("limit", limit)
                .query((rs, n) -> incident(rs)).list();
    }

    UUID insert(UUID ticketId, String state, String title, String reason, List<UUID> services, UUID actor,
                boolean approved) {
        UUID id = Ids.newId();
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_incident (id, tenant_id, ticket_id, state, public_title, reason, service_ids,
                    declared_by, declared_at, approved_by, approved_at, created_at, created_by, updated_at, updated_by,
                    version)
                values (:id, :tenant, :ticket, :state, :title, :reason, cast(:services as uuid[]), :actor, :now,
                    :approvedBy, :approvedAt, :now, :actor, :now, :actor, 1)""")
                .param("id", id).param("tenant", TenantContext.require()).param("ticket", ticketId)
                .param("state", state).param("title", title).param("reason", reason)
                .param("services", Db.array(services)).param("actor", actor).param("now", Db.ts(now))
                .param("approvedBy", approved ? actor : null).param("approvedAt", approved ? Db.ts(now) : null)
                .update();
        return id;
    }

    void update(Incident i, UUID actor) {
        jdbc.sql("""
                update mim_incident set state = :state, public_title = :title, hypothesis = :hypothesis,
                    service_ids = cast(:services as uuid[]), approved_by = :approvedBy, approved_at = :approvedAt,
                    restored_at = :restoredAt, closed_at = :closedAt, next_update_at = :nextUpdate, pir = :pir,
                    problem_id = :problem, updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", i.id()).param("state", i.state()).param("title", i.publicTitle())
                .param("hypothesis", i.hypothesis()).param("services", Db.array(i.serviceIds()))
                .param("approvedBy", i.approvedBy()).param("approvedAt", Db.ts(i.approvedAt()))
                .param("restoredAt", Db.ts(i.restoredAt())).param("closedAt", Db.ts(i.closedAt()))
                .param("nextUpdate", Db.ts(i.nextUpdateAt())).param("pir", i.pir()).param("problem", i.problemId())
                .param("now", Db.ts(Db.now())).param("actor", actor)
                .update();
    }

    // ------------------------------------------------------------------ роли

    Map<String, UUID> roles(UUID mimId) {
        Map<String, UUID> out = new java.util.LinkedHashMap<>();
        jdbc.sql("select role, user_id from mim_role where mim_id = :m").param("m", mimId)
                .query((rs, n) -> Map.entry(rs.getString("role"), Db.uuid(rs, "user_id"))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    void setRole(UUID mimId, String role, UUID userId, UUID actor) {
        Instant now = Db.now();
        if (userId == null) {
            jdbc.sql("delete from mim_role where mim_id = :m and role = :r").param("m", mimId).param("r", role)
                    .update();
            return;
        }
        jdbc.sql("""
                insert into mim_role (tenant_id, mim_id, role, user_id, created_at, created_by, updated_at, updated_by)
                values (:tenant, :m, :r, :u, :now, :actor, :now, :actor)
                on conflict (tenant_id, mim_id, role) do update set user_id = excluded.user_id,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by,
                    version = mim_role.version + 1""")
                .param("tenant", TenantContext.require()).param("m", mimId).param("r", role).param("u", userId)
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
    }

    // ------------------------------------------------------------------ хронология

    void event(UUID mimId, String kind, Instant at, String text, UUID actor, Map<String, ?> meta) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_event (id, tenant_id, mim_id, kind, at, text, actor_id, meta, created_at, created_by,
                    updated_at, updated_by)
                values (:id, :tenant, :m, :kind, :at, :text, :actor, cast(:meta as jsonb), :now, :actor, :now,
                    :actor)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("m", mimId)
                .param("kind", kind).param("at", Db.ts(at == null ? now : at))
                .param("text", text == null ? "" : text).param("actor", actor)
                .param("meta", json.writeValueAsString(meta == null ? Map.of() : meta)).param("now", Db.ts(now))
                .update();
    }

    List<EventView> events(UUID mimId) {
        return jdbc.sql("select * from mim_event where mim_id = :m order by at, created_at, id")
                .param("m", mimId)
                .query((rs, n) -> new EventView(Db.uuid(rs, "id"), rs.getString("kind"), Db.instant(rs, "at"),
                        rs.getString("text"), Db.uuid(rs, "actor_id"), json.readValue(rs.getString("meta"), MAP)))
                .list();
    }

    // ------------------------------------------------------------------ задачи

    UUID addTask(UUID mimId, String title, UUID assignee, UUID actor) {
        UUID id = Ids.newId();
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_task (id, tenant_id, mim_id, title, assignee_id, created_at, created_by, updated_at,
                    updated_by, version)
                values (:id, :tenant, :m, :title, :assignee, :now, :actor, :now, :actor, 1)""")
                .param("id", id).param("tenant", TenantContext.require()).param("m", mimId).param("title", title)
                .param("assignee", assignee).param("now", Db.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    Optional<TaskView> setTaskDone(UUID mimId, UUID taskId, boolean done, UUID actor) {
        Instant now = Db.now();
        int n = jdbc.sql("""
                update mim_task set done = :done, done_at = case when :done then cast(:now as timestamptz) end,
                    updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and mim_id = :m""")
                .param("done", done).param("now", Db.ts(now)).param("actor", actor).param("id", taskId)
                .param("m", mimId).update();
        return n == 0 ? Optional.empty() : tasks(mimId).stream().filter(t -> t.id().equals(taskId)).findFirst();
    }

    List<TaskView> tasks(UUID mimId) {
        return jdbc.sql("select * from mim_task where mim_id = :m order by created_at, id").param("m", mimId)
                .query((rs, n) -> new TaskView(Db.uuid(rs, "id"), rs.getString("title"), Db.uuid(rs, "assignee_id"),
                        rs.getBoolean("done"), Db.instant(rs, "done_at"), Db.instant(rs, "created_at")))
                .list();
    }

    // ------------------------------------------------------------------ сообщения

    UUID addUpdate(UUID mimId, MimDtos.Publish p, UUID actor) {
        UUID id = Ids.newId();
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_update (id, tenant_id, mim_id, audience, title, body, status_page, next_update_at,
                    published_at, published_by, created_at, created_by, updated_at, updated_by)
                values (:id, :tenant, :m, :audience, :title, :body, :page, :next, :now, :actor, :now, :actor, :now,
                    :actor)""")
                .param("id", id).param("tenant", TenantContext.require()).param("m", mimId)
                .param("audience", p.audience()).param("title", p.title()).param("body", p.body())
                .param("page", p.onStatusPage()).param("next", Db.ts(p.nextUpdateAt())).param("now", Db.ts(now))
                .param("actor", actor)
                .update();
        return id;
    }

    List<UpdateView> updates(UUID mimId) {
        return jdbc.sql("select * from mim_update where mim_id = :m order by published_at desc, id desc")
                .param("m", mimId).query((rs, n) -> update(rs)).list();
    }

    /** Последнее сообщение для страницы статуса. */
    Optional<UpdateView> lastStatusUpdate(UUID mimId) {
        return jdbc.sql("""
                select * from mim_update where mim_id = :m and status_page and audience = 'users'
                 order by published_at desc, id desc limit 1""")
                .param("m", mimId).query((rs, n) -> update(rs)).optional();
    }

    private static UpdateView update(ResultSet rs) throws SQLException {
        return new UpdateView(Db.uuid(rs, "id"), rs.getString("audience"), rs.getString("title"),
                rs.getString("body"), rs.getBoolean("status_page"), Db.instant(rs, "next_update_at"),
                Db.instant(rs, "published_at"), Db.uuid(rs, "published_by"));
    }

    // ------------------------------------------------------------------ шаблоны и цепочки

    List<Template> templates(boolean onlyActive) {
        return jdbc.sql("select * from mim_template" + (onlyActive ? " where active" : "")
                        + " order by position, name, id")
                .query((rs, n) -> new Template(Db.uuid(rs, "id"), rs.getString("name"), rs.getString("audience"),
                        rs.getString("title"), rs.getString("body"), rs.getInt("position"), rs.getBoolean("active"),
                        rs.getLong("version")))
                .list();
    }

    Optional<Template> template(UUID id, boolean lock) {
        return templates(false).stream().filter(t -> t.id().equals(id)).findFirst()
                .map(t -> lock ? lockTemplate(t) : t);
    }

    private Template lockTemplate(Template t) {
        jdbc.sql("select 1 from mim_template where id = :id for update").param("id", t.id()).query(Integer.class)
                .single();
        return t;
    }

    UUID saveTemplate(UUID id, MimDtos.SaveTemplate in, UUID actor) {
        Instant now = Db.now();
        if (id == null) {
            id = Ids.newId();
            jdbc.sql("""
                    insert into mim_template (id, tenant_id, name, audience, title, body, position, active, created_at,
                        created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :name, :audience, :title, :body, :position, :active, :now, :actor, :now,
                        :actor, 1)""")
                    .param("id", id).param("tenant", TenantContext.require()).param("name", in.name())
                    .param("audience", in.audience()).param("title", in.title()).param("body", in.body())
                    .param("position", in.position() == null ? 0 : in.position())
                    .param("active", in.active() == null || in.active()).param("now", Db.ts(now))
                    .param("actor", actor)
                    .update();
            return id;
        }
        jdbc.sql("""
                update mim_template set name = :name, audience = :audience, title = :title, body = :body,
                    position = coalesce(:position, position), active = coalesce(:active, active), updated_at = :now,
                    updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", id).param("name", in.name()).param("audience", in.audience())
                .param("title", in.title()).param("body", in.body()).param("position", in.position())
                .param("active", in.active()).param("now", Db.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    List<Chain> chains(boolean onlyActive) {
        return jdbc.sql("select * from mim_chain" + (onlyActive ? " where active" : "") + " order by name, id")
                .query((rs, n) -> new Chain(Db.uuid(rs, "id"), rs.getString("name"),
                        json.readValue(rs.getString("steps"), STEPS), rs.getBoolean("active"), rs.getLong("version")))
                .list();
    }

    Optional<Chain> chain(UUID id) {
        return chains(false).stream().filter(c -> c.id().equals(id)).findFirst();
    }

    UUID saveChain(UUID id, MimDtos.SaveChain in, UUID actor) {
        Instant now = Db.now();
        String steps = json.writeValueAsString(in.steps());
        if (id == null) {
            id = Ids.newId();
            jdbc.sql("""
                    insert into mim_chain (id, tenant_id, name, steps, active, created_at, created_by, updated_at,
                        updated_by, version)
                    values (:id, :tenant, :name, cast(:steps as jsonb), :active, :now, :actor, :now, :actor, 1)""")
                    .param("id", id).param("tenant", TenantContext.require()).param("name", in.name())
                    .param("steps", steps).param("active", in.active() == null || in.active())
                    .param("now", Db.ts(now)).param("actor", actor)
                    .update();
            return id;
        }
        jdbc.sql("""
                update mim_chain set name = :name, steps = cast(:steps as jsonb), active = coalesce(:active, active),
                    updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", id).param("name", in.name()).param("steps", steps).param("active", in.active())
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    // ------------------------------------------------------------------ оповещение дежурных

    private Page page(ResultSet rs) throws SQLException {
        return new Page(Db.uuid(rs, "id"), Db.uuid(rs, "mim_id"), Db.uuid(rs, "chain_id"), rs.getString("name"),
                json.readValue(rs.getString("steps"), STEPS), rs.getString("channel"), rs.getInt("step"),
                rs.getString("state"), Db.instant(rs, "next_step_at"), Db.uuid(rs, "acked_by"),
                Db.instant(rs, "acked_at"), Db.instant(rs, "created_at"), rs.getLong("version"));
    }

    UUID insertPage(UUID mimId, UUID chainId, String name, List<Step> steps, String channel, UUID actor) {
        UUID id = Ids.newId();
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_page (id, tenant_id, mim_id, chain_id, name, steps, channel, step, state, created_at,
                    created_by, updated_at, updated_by, version)
                values (:id, :tenant, :m, :chain, :name, cast(:steps as jsonb), :channel, 0, 'paging', :now, :actor,
                    :now, :actor, 1)""")
                .param("id", id).param("tenant", TenantContext.require()).param("m", mimId).param("chain", chainId)
                .param("name", name).param("steps", json.writeValueAsString(steps)).param("channel", channel)
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    Optional<Page> findPage(UUID id, boolean lock) {
        return jdbc.sql("select * from mim_page where id = :id" + (lock ? " for update" : "")).param("id", id)
                .query((rs, n) -> page(rs)).optional();
    }

    List<Page> pages(UUID mimId) {
        return jdbc.sql("select * from mim_page where mim_id = :m order by created_at desc, id desc")
                .param("m", mimId).query((rs, n) -> page(rs)).list();
    }

    void updatePage(UUID id, int step, String state, Instant nextStepAt, UUID ackedBy, Instant ackedAt) {
        jdbc.sql("""
                update mim_page set step = :step, state = :state, next_step_at = :next, acked_by = :ackedBy,
                    acked_at = :ackedAt, updated_at = :now, version = version + 1
                 where id = :id""")
                .param("id", id).param("step", step).param("state", state).param("next", Db.ts(nextStepAt))
                .param("ackedBy", ackedBy).param("ackedAt", Db.ts(ackedAt)).param("now", Db.ts(Db.now()))
                .update();
    }

    void addTarget(UUID pageId, int step, UUID userId) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into mim_page_target (tenant_id, page_id, step, user_id, notified_at, created_at, updated_at)
                values (:tenant, :p, :step, :u, :now, :now, :now) on conflict do nothing""")
                .param("tenant", TenantContext.require()).param("p", pageId).param("step", step).param("u", userId)
                .param("now", Db.ts(now))
                .update();
    }

    /** @return true — пользователь был среди оповещённых */
    boolean ackTarget(UUID pageId, UUID userId, Instant at) {
        return jdbc.sql("""
                update mim_page_target set acked_at = coalesce(acked_at, :at), updated_at = :at
                 where page_id = :p and user_id = :u""")
                .param("p", pageId).param("u", userId).param("at", Db.ts(at)).update() > 0;
    }

    List<TargetView> targets(UUID pageId) {
        return jdbc.sql("select * from mim_page_target where page_id = :p order by step, notified_at, user_id")
                .param("p", pageId)
                .query((rs, n) -> new TargetView(rs.getInt("step"), Db.uuid(rs, "user_id"),
                        Db.instant(rs, "notified_at"), Db.instant(rs, "acked_at")))
                .list();
    }

    /** Оповещён ли пользователь хоть раз по этому крупному инциденту: даёт право читать командный центр. */
    boolean isPaged(UUID mimId, UUID userId) {
        return jdbc.sql("""
                select exists (select 1 from mim_page_target t join mim_page p on p.id = t.page_id
                                where p.mim_id = :m and t.user_id = :u)""")
                .param("m", mimId).param("u", userId).query(Boolean.class).single();
    }

    record Due(UUID tenantId, UUID id) {
    }

    List<Due> duePages(Instant now, int limit) {
        return jdbc.sql("select tenant_id, id from mim_due_pages(:now, :limit)")
                .param("now", Db.ts(now)).param("limit", limit)
                .query((rs, n) -> new Due(Db.uuid(rs, "tenant_id"), Db.uuid(rs, "id"))).list();
    }

    private static List<UUID> uuids(Array arr) throws SQLException {
        if (arr == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) arr.getArray()).map(o -> (UUID) o).toList();
    }
}
