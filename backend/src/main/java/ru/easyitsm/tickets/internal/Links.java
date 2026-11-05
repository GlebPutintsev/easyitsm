package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Связи заявок (TCK-05). Хранится одна каноническая запись, с другой стороны показывается обратный вид:
 * parent_of/child_of, duplicate_of/duplicated_by, blocks/blocked_by, caused_by/causes, relates_to.
 */
@Component
class Links {

    /** Вид с точки зрения заявки → (канонический вид, эта заявка — источник). */
    private static final Map<String, Canon> CANON = Map.of(
            "parent_of", new Canon("parent_of", true), "child_of", new Canon("parent_of", false),
            "duplicate_of", new Canon("duplicate_of", true), "duplicated_by", new Canon("duplicate_of", false),
            "blocks", new Canon("blocks", true), "blocked_by", new Canon("blocks", false),
            "caused_by", new Canon("caused_by", true), "causes", new Canon("caused_by", false),
            "relates_to", new Canon("relates_to", true));
    private static final Map<String, String> INVERSE = Map.of("parent_of", "child_of", "duplicate_of", "duplicated_by",
            "blocks", "blocked_by", "caused_by", "causes", "relates_to", "relates_to");

    private record Canon(String kind, boolean thisIsSource) {
    }

    record Link(UUID id, String kind, UUID otherId, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    Links(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Link> list(UUID ticketId) {
        return jdbc.sql("""
                select id, kind, source_id, target_id, created_at from ticket_link
                 where source_id = :t or target_id = :t order by created_at, id""")
                .param("t", ticketId)
                .query((rs, n) -> {
                    UUID source = Sql.uuid(rs, "source_id");
                    String kind = rs.getString("kind");
                    boolean mine = source.equals(ticketId);
                    return new Link(Sql.uuid(rs, "id"), mine ? kind : INVERSE.get(kind),
                            mine ? Sql.uuid(rs, "target_id") : source, Sql.instant(rs, "created_at"));
                })
                .list();
    }

    UUID add(UUID ticketId, UUID otherId, String kind, UUID actor) {
        if (ticketId.equals(otherId)) {
            throw TicketProblem.clash("link_cycle", "Нельзя связать заявку с собой");
        }
        Canon c = CANON.get(kind);
        UUID source = c.thisIsSource() ? ticketId : otherId;
        UUID target = c.thisIsSource() ? otherId : ticketId;
        if (c.kind().equals("relates_to") && source.compareTo(target) > 0) {
            UUID tmp = source;
            source = target;
            target = tmp;
        }
        if (c.kind().equals("parent_of") && isAncestor(target, source)) {
            throw TicketProblem.clash("link_cycle", "Связь создаёт цикл родителей");
        }
        Instant now = Sql.now();
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                insert into ticket_link (id, tenant_id, source_id, target_id, kind, created_at, created_by, updated_at,
                    updated_by, version)
                values (:id, :tenant, :s, :t, :kind, :now, :actor, :now, :actor, 0)
                on conflict (tenant_id, source_id, target_id, kind) do nothing""")
                .param("id", id).param("tenant", TenantContext.require()).param("s", source).param("t", target)
                .param("kind", c.kind()).param("now", Sql.ts(now)).param("actor", actor)
                .update();
        if (n == 0) {
            throw TicketProblem.clash("link_exists", "Такая связь уже есть");
        }
        return id;
    }

    /** Удаляет связь, если она касается этой заявки. @return id другой стороны */
    UUID remove(UUID ticketId, UUID linkId) {
        return jdbc.sql("""
                delete from ticket_link where id = :id and (source_id = :t or target_id = :t)
                returning case when source_id = :t then target_id else source_id end as other""")
                .param("id", linkId).param("t", ticketId)
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.notFound("Связь"));
    }

    /** {@code candidate} — предок {@code node} по связям parent_of (или он сам). */
    private boolean isAncestor(UUID candidate, UUID node) {
        return jdbc.sql("""
                with recursive up(id) as (
                    select cast(:node as uuid)
                    union
                    select l.source_id from ticket_link l join up on l.target_id = up.id where l.kind = 'parent_of'
                )
                select exists (select 1 from up where id = :candidate)""")
                .param("node", node).param("candidate", candidate)
                .query(Boolean.class).single();
    }
}
