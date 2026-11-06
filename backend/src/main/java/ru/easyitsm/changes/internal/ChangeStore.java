package ru.easyitsm.changes.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.changes.api.ChangeOutcome;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.ChangeType;
import ru.easyitsm.changes.api.RiskLevel;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** SQL изменений: {@code change_detail}, шаги и согласования. Арендатор ограничен RLS. */
@Repository
class ChangeStore {

    static final String COLUMNS = """
            c.ticket_id, c.tenant_id, c.number, c.title, c.author_id, c.group_id, c.assignee_id, c.confidential,
            c.change_type, c.state, c.template_id, c.source_ticket_id, c.goal, c.justification, c.impact, c.risks,
            c.impact_rows::text as impact_rows, c.system_criticality, c.window_start, c.window_end, c.time_zone,
            c.prep_start, c.downtime_minutes, c.ci_ids, c.service_ids, c.risk_answers::text as risk_answers,
            c.risk_score, c.risk_level, c.risk_manual, c.risk_reason, c.risk_assessed_at, c.risk_assessed_by,
            c.conflicts_ack, c.submitted_at, c.approved_at, c.post_approval_due_at, c.actual_start, c.actual_end,
            c.rollback_started_at, c.outcome, c.conclusion, c.review::text as review, c.closed_at, c.cancel_reason,
            c.pipeline::text as pipeline, c.created_at, c.created_by, c.updated_at, c.updated_by, c.version""";

    /** Порядок фаз плана: как в согласованной форме плана работ. */
    static final List<String> PHASES = List.of("prepare", "implement", "verify", "rollback");

    private final JdbcClient jdbc;
    private final Json json;

    ChangeStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- change_detail

    Optional<ChangeRow> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from change_detail c where c.ticket_id = :id")
                .param("id", id).query(this::map).optional();
    }

    /** С блокировкой строки до конца транзакции: действия над одним изменением идут последовательно. */
    Optional<ChangeRow> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from change_detail c where c.ticket_id = :id for update")
                .param("id", id).query(this::map).optional();
    }

    boolean exists(UUID id) {
        return jdbc.sql("select count(*) from change_detail where ticket_id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    List<ChangeRow> query(String whereAndOrder, Map<String, Object> params) {
        return jdbc.sql("select " + COLUMNS + " from change_detail c " + whereAndOrder).params(params)
                .query(this::map).list();
    }

    void insert(ChangeRow r) {
        Instant now = Sql.now();
        r.tenantId = TenantContext.require();
        r.createdAt = now;
        r.updatedAt = now;
        r.createdBy = CurrentActor.userId().orElse(r.authorId);
        r.updatedBy = r.createdBy;
        r.version = 0;
        jdbc.sql("""
                insert into change_detail (ticket_id, tenant_id, number, title, author_id, group_id, assignee_id,
                    confidential, change_type, state, template_id, source_ticket_id, goal, justification, impact, risks,
                    impact_rows, system_criticality, window_start, window_end, time_zone, prep_start, downtime_minutes,
                    ci_ids, service_ids, risk_answers, risk_score, risk_level, risk_manual, risk_reason,
                    risk_assessed_at, risk_assessed_by, conflicts_ack, submitted_at, approved_at, post_approval_due_at,
                    actual_start, actual_end, rollback_started_at, outcome, conclusion, review, closed_at,
                    cancel_reason, pipeline, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenantId, :number, :title, :authorId, :groupId, :assigneeId, :confidential, :type,
                    :state, :templateId, :sourceTicketId, :goal, :justification, :impact, :risks,
                    cast(:impactRows as jsonb), :systemCriticality, :windowStart, :windowEnd, :timeZone, :prepStart,
                    :downtimeMinutes, cast(:ciIds as uuid[]), cast(:serviceIds as uuid[]), cast(:riskAnswers as jsonb),
                    :riskScore, :riskLevel, :riskManual, :riskReason, :riskAssessedAt, :riskAssessedBy, :conflictsAck,
                    :submittedAt, :approvedAt, :postApprovalDueAt, :actualStart, :actualEnd, :rollbackStartedAt,
                    :outcome, :conclusion, cast(:review as jsonb), :closedAt, :cancelReason, cast(:pipeline as jsonb),
                    :createdAt, :createdBy, :updatedAt, :updatedBy, :version)""")
                .params(params(r)).update();
    }

    /**
     * Сохраняет строку, если её версия не изменилась с чтения, и увеличивает версию.
     *
     * @return false — строку изменил кто-то другой
     */
    boolean update(ChangeRow r) {
        long expected = r.version;
        r.updatedAt = Sql.now();
        r.updatedBy = CurrentActor.userId().orElse(null);
        r.version = expected + 1;
        Map<String, Object> p = params(r);
        p.put("expected", expected);
        int n = jdbc.sql("""
                update change_detail set number = :number, title = :title, author_id = :authorId, group_id = :groupId,
                    assignee_id = :assigneeId, confidential = :confidential, change_type = :type, state = :state,
                    template_id = :templateId, source_ticket_id = :sourceTicketId, goal = :goal,
                    justification = :justification, impact = :impact, risks = :risks,
                    impact_rows = cast(:impactRows as jsonb), system_criticality = :systemCriticality,
                    window_start = :windowStart, window_end = :windowEnd, time_zone = :timeZone,
                    prep_start = :prepStart, downtime_minutes = :downtimeMinutes, ci_ids = cast(:ciIds as uuid[]),
                    service_ids = cast(:serviceIds as uuid[]), risk_answers = cast(:riskAnswers as jsonb),
                    risk_score = :riskScore, risk_level = :riskLevel, risk_manual = :riskManual,
                    risk_reason = :riskReason, risk_assessed_at = :riskAssessedAt, risk_assessed_by = :riskAssessedBy,
                    conflicts_ack = :conflictsAck, submitted_at = :submittedAt, approved_at = :approvedAt,
                    post_approval_due_at = :postApprovalDueAt, actual_start = :actualStart, actual_end = :actualEnd,
                    rollback_started_at = :rollbackStartedAt, outcome = :outcome, conclusion = :conclusion,
                    review = cast(:review as jsonb), closed_at = :closedAt, cancel_reason = :cancelReason,
                    pipeline = cast(:pipeline as jsonb), updated_at = :updatedAt, updated_by = :updatedBy,
                    version = :version
                 where ticket_id = :id and version = :expected""").params(p).update();
        if (n == 0) {
            r.version = expected;
        }
        return n > 0;
    }

    /** Копия полей заявки без смены версии изменения: номер, тема, автор, группа, исполнитель, конфиденциальность. */
    void syncTicket(UUID id, String number, String title, UUID authorId, UUID groupId, UUID assigneeId,
                    boolean confidential) {
        jdbc.sql("""
                update change_detail set number = :number, title = :title, author_id = :author, group_id = :group,
                    assignee_id = :assignee, confidential = :confidential
                 where ticket_id = :id and (number, title, author_id, group_id, assignee_id, confidential)
                       is distinct from (:number, :title, :author, :group, :assignee, :confidential)""")
                .param("id", id).param("number", number).param("title", title).param("author", authorId)
                .param("group", groupId).param("assignee", assigneeId).param("confidential", confidential)
                .update();
    }

    private Map<String, Object> params(ChangeRow r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", r.id);
        p.put("tenantId", r.tenantId);
        p.put("number", r.number);
        p.put("title", r.title);
        p.put("authorId", r.authorId);
        p.put("groupId", r.groupId);
        p.put("assigneeId", r.assigneeId);
        p.put("confidential", r.confidential);
        p.put("type", r.type.code());
        p.put("state", r.state.code());
        p.put("templateId", r.templateId);
        p.put("sourceTicketId", r.sourceTicketId);
        p.put("goal", r.goal);
        p.put("justification", r.justification);
        p.put("impact", r.impact);
        p.put("risks", r.risks);
        p.put("impactRows", json.write(r.impactRows));
        p.put("systemCriticality", r.systemCriticality);
        p.put("windowStart", Sql.ts(r.windowStart));
        p.put("windowEnd", Sql.ts(r.windowEnd));
        p.put("timeZone", r.timeZone);
        p.put("prepStart", Sql.ts(r.prepStart));
        p.put("downtimeMinutes", r.downtimeMinutes);
        p.put("ciIds", Sql.array(r.ciIds));
        p.put("serviceIds", Sql.array(r.serviceIds));
        p.put("riskAnswers", json.write(r.riskAnswers));
        p.put("riskScore", r.riskScore);
        p.put("riskLevel", r.riskLevel == null ? null : r.riskLevel.code());
        p.put("riskManual", r.riskManual);
        p.put("riskReason", r.riskReason);
        p.put("riskAssessedAt", Sql.ts(r.riskAssessedAt));
        p.put("riskAssessedBy", r.riskAssessedBy);
        p.put("conflictsAck", r.conflictsAck);
        p.put("submittedAt", Sql.ts(r.submittedAt));
        p.put("approvedAt", Sql.ts(r.approvedAt));
        p.put("postApprovalDueAt", Sql.ts(r.postApprovalDueAt));
        p.put("actualStart", Sql.ts(r.actualStart));
        p.put("actualEnd", Sql.ts(r.actualEnd));
        p.put("rollbackStartedAt", Sql.ts(r.rollbackStartedAt));
        p.put("outcome", r.outcome == null ? null : r.outcome.code());
        p.put("conclusion", r.conclusion);
        p.put("review", json.write(r.review));
        p.put("closedAt", Sql.ts(r.closedAt));
        p.put("cancelReason", r.cancelReason);
        p.put("pipeline", r.pipeline == null ? null : json.write(r.pipeline));
        p.put("createdAt", Sql.ts(r.createdAt));
        p.put("createdBy", r.createdBy);
        p.put("updatedAt", Sql.ts(r.updatedAt));
        p.put("updatedBy", r.updatedBy);
        p.put("version", r.version);
        return p;
    }

    private ChangeRow map(ResultSet rs, int n) throws SQLException {
        ChangeRow r = new ChangeRow();
        r.id = Sql.uuid(rs, "ticket_id");
        r.tenantId = Sql.uuid(rs, "tenant_id");
        r.number = rs.getString("number");
        r.title = rs.getString("title");
        r.authorId = Sql.uuid(rs, "author_id");
        r.groupId = Sql.uuid(rs, "group_id");
        r.assigneeId = Sql.uuid(rs, "assignee_id");
        r.confidential = rs.getBoolean("confidential");
        r.type = ChangeType.fromCode(rs.getString("change_type"));
        r.state = ChangeState.fromCode(rs.getString("state"));
        r.templateId = Sql.uuid(rs, "template_id");
        r.sourceTicketId = Sql.uuid(rs, "source_ticket_id");
        r.goal = rs.getString("goal");
        r.justification = rs.getString("justification");
        r.impact = rs.getString("impact");
        r.risks = rs.getString("risks");
        r.impactRows = json.list(rs.getString("impact_rows"));
        r.systemCriticality = rs.getString("system_criticality");
        r.windowStart = Sql.instant(rs, "window_start");
        r.windowEnd = Sql.instant(rs, "window_end");
        r.timeZone = rs.getString("time_zone");
        r.prepStart = Sql.instant(rs, "prep_start");
        r.downtimeMinutes = Sql.integer(rs, "downtime_minutes");
        r.ciIds = Sql.uuids(rs, "ci_ids");
        r.serviceIds = Sql.uuids(rs, "service_ids");
        r.riskAnswers = json.map(rs.getString("risk_answers"));
        r.riskScore = Sql.integer(rs, "risk_score");
        String risk = rs.getString("risk_level");
        r.riskLevel = risk == null ? null : RiskLevel.fromCode(risk);
        r.riskManual = rs.getBoolean("risk_manual");
        r.riskReason = rs.getString("risk_reason");
        r.riskAssessedAt = Sql.instant(rs, "risk_assessed_at");
        r.riskAssessedBy = Sql.uuid(rs, "risk_assessed_by");
        r.conflictsAck = rs.getString("conflicts_ack");
        r.submittedAt = Sql.instant(rs, "submitted_at");
        r.approvedAt = Sql.instant(rs, "approved_at");
        r.postApprovalDueAt = Sql.instant(rs, "post_approval_due_at");
        r.actualStart = Sql.instant(rs, "actual_start");
        r.actualEnd = Sql.instant(rs, "actual_end");
        r.rollbackStartedAt = Sql.instant(rs, "rollback_started_at");
        String outcome = rs.getString("outcome");
        r.outcome = outcome == null ? null : ChangeOutcome.fromCode(outcome);
        r.conclusion = rs.getString("conclusion");
        r.review = json.map(rs.getString("review"));
        r.closedAt = Sql.instant(rs, "closed_at");
        r.cancelReason = rs.getString("cancel_reason");
        String pipeline = rs.getString("pipeline");
        r.pipeline = pipeline == null ? null : json.map(pipeline);
        r.createdAt = Sql.instant(rs, "created_at");
        r.createdBy = Sql.uuid(rs, "created_by");
        r.updatedAt = Sql.instant(rs, "updated_at");
        r.updatedBy = Sql.uuid(rs, "updated_by");
        r.version = rs.getLong("version");
        return r;
    }

    // ---------------------------------------------------------------- шаги плана

    /** Шаг плана работ (CHG-03). */
    record Step(UUID id, String phase, int seq, String title, String description, UUID ownerId, String ownerName,
                String team, String contacts, Instant plannedStart, int durationMinutes, boolean upto,
                String interruption, String checkCriteria, Integer rollbackRef, String status, String resultNote,
                Instant executedAt, UUID executedBy) {

        Step withResult(String status, String note, Instant at, UUID by) {
            return new Step(id, phase, seq, title, description, ownerId, ownerName, team, contacts, plannedStart,
                    durationMinutes, upto, interruption, checkCriteria, rollbackRef, status, note, at, by);
        }
    }

    List<Step> steps(UUID changeId) {
        return jdbc.sql("""
                select * from change_step where change_id = :id
                 order by array_position(array['prepare','implement','verify','rollback'], phase), seq""")
                .param("id", changeId).query(ChangeStore::mapStep).list();
    }

    /** Заменяет план целиком: порядок и номера шагов задаёт клиент (перетаскивание). */
    void replaceSteps(UUID changeId, List<Step> steps) {
        jdbc.sql("delete from change_step where change_id = :id").param("id", changeId).update();
        Instant now = Sql.now();
        UUID actor = CurrentActor.userId().orElse(null);
        for (Step s : steps) {
            jdbc.sql("""
                    insert into change_step (id, tenant_id, change_id, phase, seq, title, description, owner_id,
                        owner_name, team, contacts, planned_start, duration_minutes, upto, interruption,
                        check_criteria, rollback_ref, status, result_note, executed_at, executed_by, created_at,
                        created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :change, :phase, :seq, :title, :description, :ownerId, :ownerName, :team,
                        :contacts, :plannedStart, :duration, :upto, :interruption, :criteria, :rollbackRef, :status,
                        :note, :executedAt, :executedBy, :now, :actor, :now, :actor, 0)""")
                    .param("id", s.id() == null ? Ids.newId() : s.id())
                    .param("tenant", TenantContext.require())
                    .param("change", changeId)
                    .param("phase", s.phase())
                    .param("seq", s.seq())
                    .param("title", s.title())
                    .param("description", s.description() == null ? "" : s.description())
                    .param("ownerId", s.ownerId())
                    .param("ownerName", s.ownerName())
                    .param("team", s.team())
                    .param("contacts", s.contacts())
                    .param("plannedStart", Sql.ts(s.plannedStart()))
                    .param("duration", s.durationMinutes())
                    .param("upto", s.upto())
                    .param("interruption", s.interruption())
                    .param("criteria", s.checkCriteria())
                    .param("rollbackRef", s.rollbackRef())
                    .param("status", s.status() == null ? "pending" : s.status())
                    .param("note", s.resultNote())
                    .param("executedAt", Sql.ts(s.executedAt()))
                    .param("executedBy", s.executedBy())
                    .param("now", Sql.ts(now))
                    .param("actor", actor)
                    .update();
        }
    }

    void updateStepResult(UUID stepId, String status, String note, Instant at, UUID by) {
        jdbc.sql("""
                update change_step set status = :status, result_note = :note, executed_at = :at, executed_by = :by,
                    updated_at = :now, updated_by = :by, version = version + 1
                 where id = :id""")
                .param("id", stepId).param("status", status).param("note", note).param("at", Sql.ts(at))
                .param("by", by).param("now", Sql.ts(Sql.now())).update();
    }

    private static Step mapStep(ResultSet rs, int n) throws SQLException {
        return new Step(Sql.uuid(rs, "id"), rs.getString("phase"), rs.getInt("seq"), rs.getString("title"),
                rs.getString("description"), Sql.uuid(rs, "owner_id"), rs.getString("owner_name"),
                rs.getString("team"), rs.getString("contacts"), Sql.instant(rs, "planned_start"),
                rs.getInt("duration_minutes"), rs.getBoolean("upto"), rs.getString("interruption"),
                rs.getString("check_criteria"), Sql.integer(rs, "rollback_ref"), rs.getString("status"),
                rs.getString("result_note"), Sql.instant(rs, "executed_at"), Sql.uuid(rs, "executed_by"));
    }

    // ---------------------------------------------------------------- согласования

    /** Слот маршрута согласования. */
    record Approval(UUID id, UUID changeId, String kind, UUID groupId, UUID approverId, int round,
                    boolean postFactum, String decision, String comment, UUID decidedBy, Instant decidedAt,
                    Instant dueAt, Instant createdAt) {

        boolean pending() {
            return "pending".equals(decision);
        }

        boolean active() {
            return !"superseded".equals(decision);
        }
    }

    List<Approval> approvals(UUID changeId) {
        return jdbc.sql("select * from change_approval where change_id = :id order by round, created_at, id")
                .param("id", changeId).query(ChangeStore::mapApproval).list();
    }

    Optional<Approval> approval(UUID id) {
        return jdbc.sql("select * from change_approval where id = :id").param("id", id)
                .query(ChangeStore::mapApproval).optional();
    }

    int currentRound(UUID changeId) {
        Integer r = jdbc.sql("select max(round) from change_approval where change_id = :id").param("id", changeId)
                .query(Integer.class).optional().orElse(null);
        return r == null ? 0 : r;
    }

    UUID insertApproval(UUID changeId, String kind, UUID groupId, UUID approverId, int round, boolean postFactum,
                        Instant dueAt) {
        UUID id = Ids.newId();
        Instant now = Sql.now();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into change_approval (id, tenant_id, change_id, kind, group_id, approver_id, round, post_factum,
                    decision, due_at, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :change, :kind, :group, :approver, :round, :post, 'pending', :due, :now, :actor,
                    :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("change", changeId)
                .param("kind", kind).param("group", groupId).param("approver", approverId).param("round", round)
                .param("post", postFactum).param("due", Sql.ts(dueAt)).param("now", Sql.ts(now))
                .param("actor", actor).update();
        return id;
    }

    /** @return false — слот уже решён или заменён */
    boolean decide(UUID approvalId, String decision, String comment, UUID by, Instant at) {
        return jdbc.sql("""
                update change_approval set decision = :decision, comment = :comment, decided_by = :by,
                    decided_at = :at, updated_at = :at, updated_by = :by, version = version + 1
                 where id = :id and decision = 'pending'""")
                .param("id", approvalId).param("decision", decision).param("comment", comment).param("by", by)
                .param("at", Sql.ts(at)).update() > 0;
    }

    /** Открытые слоты прежнего маршрута больше не нужны: перепланирование, отмена, возврат на оценку. */
    void supersedePending(UUID changeId, boolean postFactum) {
        jdbc.sql("""
                update change_approval set decision = 'superseded', updated_at = :now, version = version + 1
                 where change_id = :id and decision = 'pending' and post_factum = :post""")
                .param("id", changeId).param("post", postFactum).param("now", Sql.ts(Sql.now())).update();
    }

    void setPostFactumDue(UUID changeId, Instant due) {
        jdbc.sql("update change_approval set due_at = :due where change_id = :id and post_factum and decision = 'pending'")
                .param("id", changeId).param("due", Sql.ts(due)).update();
    }

    /**
     * Слоты, которые может решить пользователь: менеджер изменений — слоты {@code manager}, участник группы —
     * слоты группы, назначенный согласующий — свои. Собственные изменения не попадают (ACC-05).
     */
    List<Approval> pendingFor(UUID userId, boolean isManager, List<UUID> groupIds, int limit) {
        return jdbc.sql("""
                select a.* from change_approval a join change_detail c on c.ticket_id = a.change_id
                 where a.decision = 'pending' and c.author_id <> :user
                   and ((a.kind = 'manager' and :manager)
                        or (a.kind = 'group' and a.group_id = any(cast(:groups as uuid[])))
                        or (a.kind = 'user' and a.approver_id = :user))
                 order by coalesce(a.due_at, c.window_start, a.created_at), a.id limit :limit""")
                .param("user", userId).param("manager", isManager).param("groups", Sql.array(groupIds))
                .param("limit", limit).query(ChangeStore::mapApproval).list();
    }

    private static Approval mapApproval(ResultSet rs, int n) throws SQLException {
        return new Approval(Sql.uuid(rs, "id"), Sql.uuid(rs, "change_id"), rs.getString("kind"),
                Sql.uuid(rs, "group_id"), Sql.uuid(rs, "approver_id"), rs.getInt("round"),
                rs.getBoolean("post_factum"), rs.getString("decision"), rs.getString("comment"),
                Sql.uuid(rs, "decided_by"), Sql.instant(rs, "decided_at"), Sql.instant(rs, "due_at"),
                Sql.instant(rs, "created_at"));
    }
}
