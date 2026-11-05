package ru.easyitsm.tickets.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketType;

/** SQL для таблицы ticket. Арендатор ограничен RLS, фильтр по tenant_id в запросах не нужен. */
@Repository
class TicketStore {

    static final String COLUMNS = """
            t.id, t.tenant_id, t.number, t.type, t.title, t.description, t.status, t.status_category, t.priority,
            t.impact, t.urgency, t.priority_manual, t.requester_id, t.created_for_id, t.organization_id, t.group_id,
            t.assignee_id, t.service_id, t.ci_id, t.category_id, t.catalog_item_id, t.parent_id, t.channel,
            t.confidential, t.security_group_id, t.due_at, t.pending_reason, t.pending_until, t.resolution_code,
            t.resolution_text, t.reopen_count, t.escalation_level, t.identity_check_required, t.identity_verified_at,
            t.identity_verified_by, t.identity_method, t.first_response_at, t.resolved_at, t.closed_at, t.sla_due_at,
            t.awaiting_agent, t.custom::text as custom, t.created_at, t.created_by, t.updated_at, t.updated_by,
            t.version""";

    private final JdbcClient jdbc;
    private final Json json;

    TicketStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    /** Следующий номер типа в арендаторе: INC-000001, INC-000002… Счётчик под блокировкой строки. */
    String nextNumber(TicketType type) {
        Long value = jdbc.sql("""
                insert into ticket_number_seq (tenant_id, type, last_value) values (:tenant, :type, 1)
                on conflict (tenant_id, type) do update set last_value = ticket_number_seq.last_value + 1
                returning last_value""")
                .param("tenant", TenantContext.require()).param("type", type.code())
                .query(Long.class).single();
        return "%s-%06d".formatted(type.prefix(), value);
    }

    Optional<TicketRow> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from ticket t where t.id = :id")
                .param("id", id).query(this::map).optional();
    }

    Optional<TicketRow> findByNumber(String number) {
        return jdbc.sql("select " + COLUMNS + " from ticket t where t.number = :number")
                .param("number", number.toUpperCase(java.util.Locale.ROOT)).query(this::map).optional();
    }

    /** Чтение с блокировкой строки до конца транзакции: изменения одной заявки идут последовательно. */
    Optional<TicketRow> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from ticket t where t.id = :id for update")
                .param("id", id).query(this::map).optional();
    }

    List<TicketRow> query(String whereAndOrder, Map<String, Object> params) {
        return jdbc.sql("select " + COLUMNS + " from ticket t " + whereAndOrder).params(params).query(this::map).list();
    }

    void insert(TicketRow r) {
        jdbc.sql("""
                insert into ticket (id, tenant_id, number, type, title, description, status, status_category, priority,
                    impact, urgency, priority_manual, requester_id, created_for_id, organization_id, group_id,
                    assignee_id, service_id, ci_id, category_id, catalog_item_id, parent_id, channel, confidential,
                    security_group_id, due_at, pending_reason, pending_until, resolution_code, resolution_text,
                    reopen_count, escalation_level, identity_check_required, identity_verified_at, identity_verified_by,
                    identity_method, first_response_at, resolved_at, closed_at, sla_due_at, awaiting_agent, custom,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenantId, :number, :type, :title, :description, :status, :statusCategory, :priority,
                    :impact, :urgency, :priorityManual, :requesterId, :createdForId, :organizationId, :groupId,
                    :assigneeId, :serviceId, :ciId, :categoryId, :catalogItemId, :parentId, :channel, :confidential,
                    :securityGroupId, :dueAt, :pendingReason, :pendingUntil, :resolutionCode, :resolutionText,
                    :reopenCount, :escalationLevel, :identityCheckRequired, :identityVerifiedAt, :identityVerifiedBy,
                    :identityMethod, :firstResponseAt, :resolvedAt, :closedAt, :slaDueAt, :awaitingAgent,
                    cast(:custom as jsonb), :createdAt, :createdBy, :updatedAt, :updatedBy, :version)""")
                .params(params(r))
                .update();
    }

    /** Обновление с оптимистичной блокировкой: строка должна иметь версию {@code expectedVersion}. */
    boolean update(TicketRow r, long expectedVersion) {
        Map<String, Object> p = params(r);
        p.put("expectedVersion", expectedVersion);
        int n = jdbc.sql("""
                update ticket set title = :title, description = :description, status = :status,
                    status_category = :statusCategory, priority = :priority, impact = :impact, urgency = :urgency,
                    priority_manual = :priorityManual, requester_id = :requesterId, created_for_id = :createdForId,
                    organization_id = :organizationId, group_id = :groupId, assignee_id = :assigneeId,
                    service_id = :serviceId, ci_id = :ciId, category_id = :categoryId, parent_id = :parentId,
                    confidential = :confidential, security_group_id = :securityGroupId, due_at = :dueAt,
                    pending_reason = :pendingReason, pending_until = :pendingUntil, resolution_code = :resolutionCode,
                    resolution_text = :resolutionText, reopen_count = :reopenCount, escalation_level = :escalationLevel,
                    identity_check_required = :identityCheckRequired, identity_verified_at = :identityVerifiedAt,
                    identity_verified_by = :identityVerifiedBy, identity_method = :identityMethod,
                    first_response_at = :firstResponseAt, resolved_at = :resolvedAt, closed_at = :closedAt,
                    awaiting_agent = :awaitingAgent, custom = cast(:custom as jsonb), updated_at = :updatedAt,
                    updated_by = :updatedBy, version = :version
                 where id = :id and version = :expectedVersion""")
                .params(p)
                .update();
        return n == 1;
    }

    /** Служебные поля без смены версии: время реакции, ожидание ответа, срок SLA. ETag не меняется. */
    void touchService(UUID id, java.time.Instant firstResponseAt, boolean awaitingAgent, java.time.Instant updatedAt) {
        jdbc.sql("""
                update ticket set first_response_at = coalesce(first_response_at, :fr), awaiting_agent = :aw,
                       updated_at = :now
                 where id = :id""")
                .param("fr", Sql.ts(firstResponseAt)).param("aw", awaitingAgent).param("now", Sql.ts(updatedAt))
                .param("id", id)
                .update();
    }

    void updateSlaDue(UUID id, java.time.Instant slaDueAt) {
        jdbc.sql("update ticket set sla_due_at = :due where id = :id")
                .param("due", Sql.ts(slaDueAt)).param("id", id).update();
    }

    boolean hasOpenSubtasks(UUID parentId) {
        return jdbc.sql("""
                select exists (select 1 from ticket where parent_id = :id and status_category not in ('closed', 'cancelled'))""")
                .param("id", parentId).query(Boolean.class).single();
    }

    long countInStatus(TicketType type, String status) {
        return jdbc.sql("select count(*) from ticket where type = :type and status = :status")
                .param("type", type.code()).param("status", status).query(Long.class).single();
    }

    private Map<String, Object> params(TicketRow r) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("id", r.id);
        p.put("tenantId", r.tenantId);
        p.put("number", r.number);
        p.put("type", r.type.code());
        p.put("title", r.title);
        p.put("description", r.description == null ? "" : r.description);
        p.put("status", r.status);
        p.put("statusCategory", r.statusCategory.code());
        p.put("priority", r.priority.code());
        p.put("impact", r.impact.code());
        p.put("urgency", r.urgency.code());
        p.put("priorityManual", r.priorityManual);
        p.put("requesterId", r.requesterId);
        p.put("createdForId", r.createdForId);
        p.put("organizationId", r.organizationId);
        p.put("groupId", r.groupId);
        p.put("assigneeId", r.assigneeId);
        p.put("serviceId", r.serviceId);
        p.put("ciId", r.ciId);
        p.put("categoryId", r.categoryId);
        p.put("catalogItemId", r.catalogItemId);
        p.put("parentId", r.parentId);
        p.put("channel", r.channel.code());
        p.put("confidential", r.confidential);
        p.put("securityGroupId", r.securityGroupId);
        p.put("dueAt", Sql.ts(r.dueAt));
        p.put("pendingReason", r.pendingReason);
        p.put("pendingUntil", Sql.ts(r.pendingUntil));
        p.put("resolutionCode", r.resolutionCode);
        p.put("resolutionText", r.resolutionText);
        p.put("reopenCount", r.reopenCount);
        p.put("escalationLevel", r.escalationLevel);
        p.put("identityCheckRequired", r.identityCheckRequired);
        p.put("identityVerifiedAt", Sql.ts(r.identityVerifiedAt));
        p.put("identityVerifiedBy", r.identityVerifiedBy);
        p.put("identityMethod", r.identityMethod);
        p.put("firstResponseAt", Sql.ts(r.firstResponseAt));
        p.put("resolvedAt", Sql.ts(r.resolvedAt));
        p.put("closedAt", Sql.ts(r.closedAt));
        p.put("slaDueAt", Sql.ts(r.slaDueAt));
        p.put("awaitingAgent", r.awaitingAgent);
        p.put("custom", json.write(r.custom));
        p.put("createdAt", Sql.ts(r.createdAt));
        p.put("createdBy", r.createdBy);
        p.put("updatedAt", Sql.ts(r.updatedAt));
        p.put("updatedBy", r.updatedBy);
        p.put("version", r.version);
        return p;
    }

    TicketRow map(ResultSet rs, int n) throws SQLException {
        TicketRow r = new TicketRow();
        r.id = Sql.uuid(rs, "id");
        r.tenantId = Sql.uuid(rs, "tenant_id");
        r.number = rs.getString("number");
        r.type = TicketType.fromCode(rs.getString("type"));
        r.title = rs.getString("title");
        r.description = rs.getString("description");
        r.status = rs.getString("status");
        r.statusCategory = StatusCategory.fromCode(rs.getString("status_category"));
        r.priority = Priority.fromCode(rs.getString("priority"));
        r.impact = Level.fromCode(rs.getString("impact"));
        r.urgency = Level.fromCode(rs.getString("urgency"));
        r.priorityManual = rs.getBoolean("priority_manual");
        r.requesterId = Sql.uuid(rs, "requester_id");
        r.createdForId = Sql.uuid(rs, "created_for_id");
        r.organizationId = Sql.uuid(rs, "organization_id");
        r.groupId = Sql.uuid(rs, "group_id");
        r.assigneeId = Sql.uuid(rs, "assignee_id");
        r.serviceId = Sql.uuid(rs, "service_id");
        r.ciId = Sql.uuid(rs, "ci_id");
        r.categoryId = Sql.uuid(rs, "category_id");
        r.catalogItemId = Sql.uuid(rs, "catalog_item_id");
        r.parentId = Sql.uuid(rs, "parent_id");
        r.channel = Channel.fromCode(rs.getString("channel"));
        r.confidential = rs.getBoolean("confidential");
        r.securityGroupId = Sql.uuid(rs, "security_group_id");
        r.dueAt = Sql.instant(rs, "due_at");
        r.pendingReason = rs.getString("pending_reason");
        r.pendingUntil = Sql.instant(rs, "pending_until");
        r.resolutionCode = rs.getString("resolution_code");
        r.resolutionText = rs.getString("resolution_text");
        r.reopenCount = rs.getInt("reopen_count");
        r.escalationLevel = rs.getInt("escalation_level");
        r.identityCheckRequired = rs.getBoolean("identity_check_required");
        r.identityVerifiedAt = Sql.instant(rs, "identity_verified_at");
        r.identityVerifiedBy = Sql.uuid(rs, "identity_verified_by");
        r.identityMethod = rs.getString("identity_method");
        r.firstResponseAt = Sql.instant(rs, "first_response_at");
        r.resolvedAt = Sql.instant(rs, "resolved_at");
        r.closedAt = Sql.instant(rs, "closed_at");
        r.slaDueAt = Sql.instant(rs, "sla_due_at");
        r.awaitingAgent = rs.getBoolean("awaiting_agent");
        r.custom = json.readMap(rs.getString("custom"));
        r.createdAt = Sql.instant(rs, "created_at");
        r.createdBy = Sql.uuid(rs, "created_by");
        r.updatedAt = Sql.instant(rs, "updated_at");
        r.updatedBy = Sql.uuid(rs, "updated_by");
        r.version = rs.getLong("version");
        return r;
    }
}
