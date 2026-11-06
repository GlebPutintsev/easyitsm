package ru.easyitsm.problems.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.problems.internal.ProblemDtos.Analysis;
import tools.jackson.databind.json.JsonMapper;

/** Таблицы модуля: данные проблемы, её инциденты и изменения, известные ошибки. */
@Repository
class ProblemStore {

    /** Данные проблемы сверх заявки. Строки может не быть: проблему создали в карточке заявок. */
    record Detail(UUID ticketId, String source, String rootCause, String workaround, Analysis analysis,
                  boolean closeIncidents, UUID riskAcceptedBy, Instant riskAcceptedAt, Instant riskReviewAt,
                  String riskReason, long version) {

        static Detail empty(UUID ticketId) {
            return new Detail(ticketId, "manual", "", "", Analysis.empty(), true, null, null, null, null, 0);
        }
    }

    record KnownError(UUID id, UUID problemId, String title, String symptoms, String workaround, UUID serviceId,
                      UUID ciId, UUID categoryId, boolean published, Instant publishedAt, UUID publishedBy,
                      boolean fixed, Instant updatedAt, long version, double score) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ProblemStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ данные проблемы

    Optional<Detail> find(UUID ticketId) {
        return jdbc.sql("select * from problem_detail where ticket_id = :id").param("id", ticketId)
                .query((rs, n) -> new Detail(Db.uuid(rs, "ticket_id"), rs.getString("source"),
                        rs.getString("root_cause"), rs.getString("workaround"),
                        json.readValue(rs.getString("analysis"), Analysis.class).normalized(),
                        rs.getBoolean("close_incidents"), Db.uuid(rs, "risk_accepted_by"),
                        Db.instant(rs, "risk_accepted_at"), Db.instant(rs, "risk_review_at"),
                        rs.getString("risk_reason"), rs.getLong("version")))
                .optional();
    }

    void insert(UUID ticketId, String source, String rootCause, Analysis analysis, UUID actor) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into problem_detail (ticket_id, tenant_id, source, root_cause, analysis, created_at, created_by,
                    updated_at, updated_by, version)
                values (:id, :tenant, :source, :root, cast(:analysis as jsonb), :now, :actor, :now, :actor, 1)
                on conflict (ticket_id) do nothing""")
                .param("id", ticketId).param("tenant", TenantContext.require()).param("source", source)
                .param("root", rootCause).param("analysis", json.writeValueAsString(analysis))
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
    }

    /** Строка под блокировкой; если её нет — создаётся пустая с версией 0. */
    Detail lock(UUID ticketId, UUID actor) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into problem_detail (ticket_id, tenant_id, created_at, created_by, updated_at, updated_by,
                    version)
                values (:id, :tenant, :now, :actor, :now, :actor, 0)
                on conflict (ticket_id) do nothing""")
                .param("id", ticketId).param("tenant", TenantContext.require()).param("now", Db.ts(now))
                .param("actor", actor)
                .update();
        jdbc.sql("select 1 from problem_detail where ticket_id = :id for update").param("id", ticketId)
                .query(Integer.class).single();
        return find(ticketId).orElseThrow();
    }

    void update(Detail d, UUID actor) {
        jdbc.sql("""
                update problem_detail set root_cause = :root, workaround = :work, analysis = cast(:analysis as jsonb),
                    close_incidents = :close, risk_accepted_by = :riskBy, risk_accepted_at = :riskAt,
                    risk_review_at = :reviewAt, risk_reason = :riskReason, updated_at = :now, updated_by = :actor,
                    version = version + 1
                 where ticket_id = :id""")
                .param("id", d.ticketId()).param("root", d.rootCause()).param("work", d.workaround())
                .param("analysis", json.writeValueAsString(d.analysis())).param("close", d.closeIncidents())
                .param("riskBy", d.riskAcceptedBy()).param("riskAt", Db.ts(d.riskAcceptedAt()))
                .param("reviewAt", Db.ts(d.riskReviewAt())).param("riskReason", d.riskReason())
                .param("now", Db.ts(Db.now())).param("actor", actor)
                .update();
    }

    // ------------------------------------------------------------------ инциденты и изменения

    void addIncident(UUID problemId, UUID incidentId, UUID actor) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into problem_incident (tenant_id, problem_id, incident_id, created_at, created_by, updated_at,
                    updated_by)
                values (:tenant, :p, :i, :now, :actor, :now, :actor) on conflict do nothing""")
                .param("tenant", TenantContext.require()).param("p", problemId).param("i", incidentId)
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
    }

    /** Проблемы, в которые модуль уже связал эти инциденты: инцидент → проблемы. */
    Map<UUID, List<UUID>> problemsOf(Collection<UUID> incidentIds) {
        Map<UUID, List<UUID>> out = new LinkedHashMap<>();
        if (incidentIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select incident_id, problem_id from problem_incident
                 where incident_id = any(cast(:ids as uuid[])) order by created_at""")
                .param("ids", Db.array(incidentIds))
                .query((rs, n) -> Map.entry(Db.uuid(rs, "incident_id"), Db.uuid(rs, "problem_id")))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(e.getValue()));
        return out;
    }

    void addChange(UUID problemId, UUID changeId, UUID actor) {
        Instant now = Db.now();
        jdbc.sql("""
                insert into problem_change (tenant_id, problem_id, change_id, created_at, created_by, updated_at,
                    updated_by)
                values (:tenant, :p, :c, :now, :actor, :now, :actor) on conflict do nothing""")
                .param("tenant", TenantContext.require()).param("p", problemId).param("c", changeId)
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
    }

    List<UUID> changes(UUID problemId) {
        return jdbc.sql("select change_id from problem_change where problem_id = :p order by created_at")
                .param("p", problemId).query(UUID.class).list();
    }

    /** Отмечает изменение выполненным. @return проблемы, для которых оно было исправлением (впервые) */
    List<UUID> completeChange(UUID changeId, Instant at) {
        return jdbc.sql("""
                update problem_change set completed_at = :at, updated_at = :at, version = version + 1
                 where change_id = :c and completed_at is null returning problem_id""")
                .param("c", changeId).param("at", Db.ts(at))
                .query(UUID.class).list();
    }

    // ------------------------------------------------------------------ известные ошибки

    private static final String KE_COLUMNS = """
            k.id, k.problem_id, k.title, k.symptoms, k.workaround, k.service_id, k.ci_id, k.category_id, k.published,
            k.published_at, k.published_by, k.fixed, k.updated_at, k.version""";

    private KnownError knownError(java.sql.ResultSet rs, boolean withScore) throws java.sql.SQLException {
        return new KnownError(Db.uuid(rs, "id"), Db.uuid(rs, "problem_id"), rs.getString("title"),
                rs.getString("symptoms"), rs.getString("workaround"), Db.uuid(rs, "service_id"),
                Db.uuid(rs, "ci_id"), Db.uuid(rs, "category_id"), rs.getBoolean("published"),
                Db.instant(rs, "published_at"), Db.uuid(rs, "published_by"), rs.getBoolean("fixed"),
                Db.instant(rs, "updated_at"), rs.getLong("version"), withScore ? rs.getDouble("score") : 0);
    }

    Optional<KnownError> knownErrorOf(UUID problemId, boolean lock) {
        return jdbc.sql("select " + KE_COLUMNS + " from known_error k where k.problem_id = :p"
                        + (lock ? " for update" : ""))
                .param("p", problemId).query((rs, n) -> knownError(rs, false)).optional();
    }

    UUID insertKnownError(UUID problemId, ProblemDtos.SaveKnownError in, UUID actor) {
        UUID id = Ids.newId();
        Instant now = Db.now();
        jdbc.sql("""
                insert into known_error (id, tenant_id, problem_id, title, symptoms, workaround, service_id, ci_id,
                    category_id, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :p, :title, :symptoms, :work, :service, :ci, :category, :now, :actor, :now,
                    :actor, 1)""")
                .param("id", id).param("tenant", TenantContext.require()).param("p", problemId)
                .param("title", in.title()).param("symptoms", nz(in.symptoms())).param("work", nz(in.workaround()))
                .param("service", in.serviceId()).param("ci", in.ciId()).param("category", in.categoryId())
                .param("now", Db.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    void updateKnownError(UUID id, ProblemDtos.SaveKnownError in, UUID actor) {
        jdbc.sql("""
                update known_error set title = :title, symptoms = :symptoms, workaround = :work, service_id = :service,
                    ci_id = :ci, category_id = :category, updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", id).param("title", in.title()).param("symptoms", nz(in.symptoms()))
                .param("work", nz(in.workaround())).param("service", in.serviceId()).param("ci", in.ciId())
                .param("category", in.categoryId()).param("now", Db.ts(Db.now())).param("actor", actor)
                .update();
    }

    void publishKnownError(UUID id, boolean published, UUID actor) {
        Instant now = Db.now();
        jdbc.sql("""
                update known_error set published = :pub,
                    published_at = case when :pub then :now else published_at end,
                    published_by = case when :pub then :actor else published_by end,
                    updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", id).param("pub", published).param("now", Db.ts(now)).param("actor", actor)
                .update();
    }

    void markFixed(UUID problemId, boolean fixed) {
        jdbc.sql("""
                update known_error set fixed = :fixed, updated_at = :now, version = version + 1
                 where problem_id = :p and fixed <> :fixed""")
                .param("p", problemId).param("fixed", fixed).param("now", Db.ts(Db.now()))
                .update();
    }

    /**
     * Подбор известных ошибок: совпадение КЕ, услуги, категории и текста (любое слово). Без условий — последние.
     * Исправленные — ниже неисправленных.
     */
    List<KnownError> search(String q, UUID serviceId, UUID ciId, UUID categoryId, boolean includeDrafts,
                            boolean onlyMatches, int limit) {
        String text = q == null ? "" : q.strip();
        return jdbc.sql("""
                with query as (
                    select case when cast(:q as text) = '' then null
                           else to_tsquery('russian', replace(plainto_tsquery('russian', :q)::text, ' & ', ' | '))
                           end as tsq
                )
                select %s,
                       (case when cast(:ci as uuid) is not null and k.ci_id = cast(:ci as uuid) then 3 else 0 end
                        + case when cast(:service as uuid) is not null and k.service_id = cast(:service as uuid) then 2 else 0 end
                        + case when cast(:category as uuid) is not null and k.category_id = cast(:category as uuid) then 1 else 0 end
                        + coalesce(ts_rank(k.search_vector, query.tsq), 0) * 4
                        - case when k.fixed then 2 else 0 end) as score
                  from known_error k, query
                 where (k.published or :drafts)
                   and (not :only
                        or (cast(:ci as uuid) is not null and k.ci_id = cast(:ci as uuid))
                        or (cast(:service as uuid) is not null and k.service_id = cast(:service as uuid))
                        or (cast(:category as uuid) is not null and k.category_id = cast(:category as uuid))
                        or (query.tsq is not null and k.search_vector @@ query.tsq))
                 order by score desc, k.updated_at desc
                 limit :limit""".formatted(KE_COLUMNS))
                .param("q", text).param("ci", ciId).param("service", serviceId).param("category", categoryId)
                .param("drafts", includeDrafts).param("only", onlyMatches).param("limit", limit)
                .query((rs, n) -> knownError(rs, true))
                .list();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
