package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** «Журнал моих входов» (10.3 ТЗ). Полный журнал аудита ведёт модуль audit по событиям identity. */
@Repository
class LoginHistory {

    private final JdbcClient jdbc;

    LoginHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Entry(Instant at, String result, String method, String ip, String userAgent) {
    }

    void record(UUID userId, String login, String result, String method, RequestInfo info) {
        jdbc.sql("""
                insert into login_event (id, tenant_id, user_id, login, result, method, ip, user_agent)
                values (:id, :tenant, :user, :login, :result, :method, :ip, :ua)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("user", userId)
                .param("login", login == null ? "" : truncate(login)).param("result", result).param("method", method)
                .param("ip", info.ip()).param("ua", info.userAgent())
                .update();
    }

    List<Entry> recent(UUID userId, int limit) {
        return jdbc.sql("""
                select at, result, method, ip, user_agent from login_event
                 where user_id = :user order by at desc limit :limit""")
                .param("user", userId).param("limit", limit)
                .query((rs, n) -> new Entry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    private static String truncate(String login) {
        return login.length() > 255 ? login.substring(0, 255) : login;
    }
}
