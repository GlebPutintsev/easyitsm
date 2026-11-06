package ru.easyitsm.assets.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;

/** История актива ({@code asset_event}) и его связи с заявками ({@code asset_ticket}). Под RLS арендатора. */
@Component
class AssetHistory {

    private final JdbcClient jdbc;

    AssetHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Entry(UUID id, String kind, String fromState, String toState, UUID fromHolder, UUID toHolder, UUID ticketId,
                 String comment, UUID actorId, Instant at) {
    }

    record Change(String kind, String fromState, String toState, UUID fromHolder, UUID toHolder, UUID ticketId,
                  String comment) {

        static Change of(String kind) {
            return new Change(kind, null, null, null, null, null, null);
        }
    }

    void record(UUID assetId, Change c, Instant at) {
        jdbc.sql("""
                        insert into asset_event (id, tenant_id, asset_id, kind, from_state, to_state, from_holder,
                                                 to_holder, ticket_id, comment, actor_id, at)
                        values (:id, app_current_tenant(), :asset, :kind, :fromState, :toState, :fromHolder,
                                :toHolder, :ticket, :comment, :actor, :at)""")
                .param("id", Ids.newId())
                .param("asset", assetId)
                .param("kind", c.kind())
                .param("fromState", c.fromState())
                .param("toState", c.toState())
                .param("fromHolder", c.fromHolder())
                .param("toHolder", c.toHolder())
                .param("ticket", c.ticketId())
                .param("comment", AssetDtos.blankToNull(c.comment()))
                .param("actor", CurrentActor.userId().orElse(null))
                .param("at", Timestamp.from(at))
                .update();
    }

    List<Entry> list(UUID assetId, int limit) {
        return jdbc.sql("""
                        select id, kind, from_state, to_state, from_holder, to_holder, ticket_id, comment, actor_id, at
                          from asset_event where asset_id = :asset
                         order by at desc, id desc limit :limit""")
                .param("asset", assetId).param("limit", limit)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("from_state"), rs.getString("to_state"), rs.getObject("from_holder", UUID.class),
                        rs.getObject("to_holder", UUID.class), rs.getObject("ticket_id", UUID.class),
                        rs.getString("comment"), rs.getObject("actor_id", UUID.class),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }

    /** @return false, если связь уже была */
    boolean link(UUID assetId, UUID ticketId, Instant at) {
        return jdbc.sql("""
                        insert into asset_ticket (tenant_id, asset_id, ticket_id, linked_by, linked_at)
                        values (app_current_tenant(), :asset, :ticket, :actor, :at)
                        on conflict do nothing""")
                .param("asset", assetId).param("ticket", ticketId)
                .param("actor", CurrentActor.userId().orElse(null)).param("at", Timestamp.from(at))
                .update() > 0;
    }

    boolean unlink(UUID assetId, UUID ticketId) {
        return jdbc.sql("delete from asset_ticket where asset_id = :asset and ticket_id = :ticket")
                .param("asset", assetId).param("ticket", ticketId).update() > 0;
    }

    /** Заявки актива, новые сверху. */
    List<UUID> tickets(UUID assetId) {
        return jdbc.sql("select ticket_id from asset_ticket where asset_id = :asset order by linked_at desc")
                .param("asset", assetId).query(UUID.class).list();
    }
}
