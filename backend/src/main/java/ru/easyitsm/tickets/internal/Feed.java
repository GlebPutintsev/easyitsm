package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Лента активности (TCK-03) и история изменений полей. Обе таблицы только дополняются. */
@Repository
class Feed {

    private final JdbcClient jdbc;
    private final Json json;

    Feed(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Entry(UUID id, String kind, String visibility, UUID authorId, boolean fromRequester, String body,
                 Map<String, Object> meta, List<UUID> mentions, Instant createdAt) {
    }

    record Change(String field, Object from, Object to, UUID actorId, String reason, Instant at) {
    }

    UUID add(UUID ticketId, String kind, String visibility, UUID authorId, boolean fromRequester, String body,
             Map<String, Object> meta, List<UUID> mentions, Instant at) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into ticket_activity (id, tenant_id, ticket_id, kind, visibility, author_id, from_requester, body,
                    meta, mentions, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :ticket, :kind, :visibility, :author, :fromRequester, :body, cast(:meta as jsonb),
                    cast(:mentions as uuid[]), :at, :author, :at, :author, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("ticket", ticketId)
                .param("kind", kind).param("visibility", visibility).param("author", authorId)
                .param("fromRequester", fromRequester).param("body", body == null ? "" : body)
                .param("meta", json.write(meta == null ? Map.of() : meta))
                .param("mentions", Sql.array(mentions)).param("at", Sql.ts(at))
                .update();
        return id;
    }

    void history(UUID ticketId, List<Change> changes) {
        for (Change c : changes) {
            jdbc.sql("""
                    insert into ticket_field_history (id, tenant_id, ticket_id, field, old_value, new_value, reason,
                        actor_id, created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :ticket, :field, cast(:old as jsonb), cast(:new as jsonb), :reason, :actor,
                        :at, :actor, :at, :actor, 0)""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("ticket", ticketId)
                    .param("field", c.field()).param("old", json.write(c.from())).param("new", json.write(c.to()))
                    .param("reason", c.reason()).param("actor", c.actorId()).param("at", Sql.ts(c.at()))
                    .update();
        }
    }

    CursorPage<Entry> page(UUID ticketId, boolean includeInternal, String visibility, PageParams page) {
        KeysetCursor after = page.keyset();
        var rows = jdbc.sql("""
                select id, kind, visibility, author_id, from_requester, body, meta::text as meta, mentions, created_at
                  from ticket_activity
                 where ticket_id = :ticket
                   and (:internal or visibility = 'public')
                   and (cast(:visibility as text) is null or visibility = :visibility)
                   and (cast(:afterId as uuid) is null or id > cast(:afterId as uuid))
                 order by id
                 limit :limit""")
                .param("ticket", ticketId).param("internal", includeInternal).param("visibility", visibility)
                .param("afterId", after == null ? null : after.id()).param("limit", page.limit() + 1)
                .query((rs, n) -> new Entry(Sql.uuid(rs, "id"), rs.getString("kind"), rs.getString("visibility"),
                        Sql.uuid(rs, "author_id"), rs.getBoolean("from_requester"), rs.getString("body"),
                        json.readMap(rs.getString("meta")), Sql.uuids(rs, "mentions"), Sql.instant(rs, "created_at")))
                .list();
        return CursorPage.of(rows, page.limit(), e -> new KeysetCursor(e.createdAt().toString(), e.id()));
    }

    CursorPage<Change> historyPage(UUID ticketId, PageParams page) {
        KeysetCursor after = page.keyset();
        record Row(UUID id, Change change) {
        }
        var rows = jdbc.sql("""
                select id, field, old_value::text as old_value, new_value::text as new_value, reason, actor_id, created_at
                  from ticket_field_history
                 where ticket_id = :ticket and (cast(:afterId as uuid) is null or id > cast(:afterId as uuid))
                 order by id
                 limit :limit""")
                .param("ticket", ticketId).param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), new Change(rs.getString("field"),
                        readValue(rs.getString("old_value")), readValue(rs.getString("new_value")),
                        Sql.uuid(rs, "actor_id"), rs.getString("reason"), Sql.instant(rs, "created_at"))))
                .list();
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.change().at().toString(), r.id()))
                .map(Row::change);
    }

    /** Ответ или заметка (kind reply/note) для других модулей: текст письма заявителю. */
    java.util.Optional<ru.easyitsm.tickets.api.TicketComment> find(UUID ticketId, UUID activityId) {
        return jdbc.sql("""
                select id, ticket_id, author_id, visibility, from_requester, body, created_at
                  from ticket_activity
                 where ticket_id = :t and id = :id and kind in ('reply', 'note')""")
                .param("t", ticketId).param("id", activityId)
                .query((rs, n) -> new ru.easyitsm.tickets.api.TicketComment(Sql.uuid(rs, "id"),
                        Sql.uuid(rs, "ticket_id"), Sql.uuid(rs, "author_id"),
                        ru.easyitsm.tickets.api.Visibility.fromCode(rs.getString("visibility")),
                        rs.getBoolean("from_requester"), rs.getString("body"), Sql.instant(rs, "created_at")))
                .optional();
    }

    /** Публичная запись ленты, если она есть, — для проверки «последний ответ от заявителя». */
    boolean exists(UUID ticketId, UUID activityId) {
        return jdbc.sql("select exists (select 1 from ticket_activity where ticket_id = :t and id = :id)")
                .param("t", ticketId).param("id", activityId).query(Boolean.class).single();
    }

    private Object readValue(String raw) {
        if (raw == null) {
            return null;
        }
        return json.read(raw, Object.class);
    }
}
