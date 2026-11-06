package ru.easyitsm.notifications.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Центр уведомлений (NTF-01): записи пользователя. Каждый видит только свои — фильтр по user_id в каждом запросе. */
@Repository
class NotificationStore {

    private final JdbcClient jdbc;

    NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record View(UUID id, String kind, String title, String body, String link, UUID ticketId, boolean read,
                Instant createdAt) {
    }

    UUID add(UUID userId, String kind, String title, String body, String link, UUID ticketId, boolean push) {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into notification (id, tenant_id, user_id, kind, title, body, link, ticket_id, push_state,
                    created_at, updated_at, version)
                values (:id, :tenant, :user, :kind, :title, :body, :link, :ticket, :push, :now, :now, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("user", userId).param("kind", kind)
                .param("title", cut(title, 500)).param("body", cut(body, 2000)).param("link", link)
                .param("ticket", ticketId).param("push", push ? "pending" : "none").param("now", now)
                .update();
        return id;
    }

    CursorPage<View> page(UUID userId, boolean unreadOnly, PageParams page) {
        KeysetCursor after = page.keyset();
        List<View> rows = jdbc.sql("""
                select id, kind, title, body, link, ticket_id, read_at is not null as read, created_at
                  from notification
                 where user_id = :user and (:unread = false or read_at is null)
                   and (cast(:after as uuid) is null or id < cast(:after as uuid))
                 order by id desc
                 limit :limit""")
                .param("user", userId).param("unread", unreadOnly).param("after", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new View(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), rs.getObject("ticket_id", UUID.class),
                        rs.getBoolean("read"), rs.getTimestamp("created_at").toInstant()))
                .list();
        return CursorPage.of(rows, page.limit(), v -> new KeysetCursor(v.createdAt().toString(), v.id()));
    }

    long unread(UUID userId) {
        return jdbc.sql("select count(*) from notification where user_id = :user and read_at is null")
                .param("user", userId).query(Long.class).single();
    }

    /** @return false — записи нет или она чужая */
    boolean markRead(UUID userId, UUID id, boolean read) {
        return jdbc.sql("""
                update notification set read_at = case when :read then coalesce(read_at, now()) else null end,
                    updated_at = now()
                 where id = :id and user_id = :user""")
                .param("id", id).param("user", userId).param("read", read).update() > 0;
    }

    int markAllRead(UUID userId) {
        return jdbc.sql("update notification set read_at = now(), updated_at = now() where user_id = :user and read_at is null")
                .param("user", userId).update();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
