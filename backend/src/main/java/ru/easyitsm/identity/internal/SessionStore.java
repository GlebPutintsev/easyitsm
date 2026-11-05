package ru.easyitsm.identity.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Серверные сессии. Токен из куки в базе не хранится, только SHA-256. Поиск по токену идёт через функцию
 * {@code identity_resolve_session}: на этом шаге арендатор ещё неизвестен.
 */
@Repository
class SessionStore {

    static final String ACTIVE = "active";
    static final String MFA = "mfa";
    static final String MFA_SETUP = "mfa_setup";

    private final JdbcClient jdbc;

    SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Resolved(UUID sessionId, UUID tenantId, UUID userId, String login, String state, String authMethod,
                    Instant createdAt, Instant lastSeenAt, Instant expiresAt, Instant reauthAt, List<String> roles) {
    }

    record Created(UUID id, String token, Instant expiresAt) {
    }

    record SessionRow(UUID id, String state, String authMethod, Instant createdAt, Instant lastSeenAt,
                      Instant expiresAt, String ip, String userAgent) {
    }

    Optional<Resolved> resolve(String token) {
        return jdbc.sql("select * from identity_resolve_session(:hash)")
                .param("hash", Secrets.sha256(token))
                .query((rs, n) -> {
                    java.sql.Array roles = rs.getArray("roles");
                    return new Resolved(rs.getObject("session_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getObject("user_id", UUID.class), rs.getString("login"), rs.getString("state"),
                            rs.getString("auth_method"), ts(rs.getTimestamp("created_at")),
                            ts(rs.getTimestamp("last_seen_at")), ts(rs.getTimestamp("expires_at")),
                            ts(rs.getTimestamp("reauth_at")),
                            roles == null ? List.of() : List.of((String[]) roles.getArray()));
                })
                .optional();
    }

    /** Новая сессия в текущем арендаторе. Токен возвращается один раз — для куки. */
    Created create(UUID userId, String state, String authMethod, Instant expiresAt, boolean reauthenticated,
                   RequestInfo info) {
        UUID id = Ids.newId();
        String token = Secrets.newToken();
        jdbc.sql("""
                insert into user_session (id, tenant_id, user_id, token_hash, state, auth_method, expires_at,
                                          reauth_at, ip, user_agent)
                values (:id, :tenant, :user, :hash, :state, :method, :expires,
                        case when :reauth then now() end, :ip, :ua)""")
                .param("id", id).param("tenant", TenantContext.require()).param("user", userId)
                .param("hash", Secrets.sha256(token)).param("state", state).param("method", authMethod)
                .param("expires", Timestamp.from(expiresAt)).param("reauth", reauthenticated)
                .param("ip", info.ip()).param("ua", info.userAgent())
                .update();
        return new Created(id, token, expiresAt);
    }

    void touch(UUID sessionId) {
        jdbc.sql("update user_session set last_seen_at = now() where id = :id and revoked_at is null")
                .param("id", sessionId).update();
    }

    void markReauthenticated(UUID sessionId) {
        jdbc.sql("update user_session set reauth_at = now(), last_seen_at = now() where id = :id and revoked_at is null")
                .param("id", sessionId).update();
    }

    boolean revoke(UUID sessionId, String reason) {
        return jdbc.sql("update user_session set revoked_at = now(), revoke_reason = :reason where id = :id and revoked_at is null")
                .param("id", sessionId).param("reason", reason).update() == 1;
    }

    /** Завершает сессию пользователя; чужую сессию не трогает (условие по user_id). */
    boolean revokeOwn(UUID userId, UUID sessionId, String reason) {
        return jdbc.sql("""
                update user_session set revoked_at = now(), revoke_reason = :reason
                 where id = :id and user_id = :user and revoked_at is null""")
                .param("id", sessionId).param("user", userId).param("reason", reason).update() == 1;
    }

    int revokeAll(UUID userId, UUID exceptSessionId, String reason) {
        return jdbc.sql("""
                update user_session set revoked_at = now(), revoke_reason = :reason
                 where user_id = :user and revoked_at is null
                   and (cast(:except as uuid) is null or id <> cast(:except as uuid))""")
                .param("user", userId).param("except", exceptSessionId).param("reason", reason).update();
    }

    List<SessionRow> listActive(UUID userId) {
        return jdbc.sql("""
                select id, state, auth_method, created_at, last_seen_at, expires_at, ip, user_agent
                  from user_session
                 where user_id = :user and revoked_at is null and expires_at > now() and state = 'active'
                 order by last_seen_at desc""")
                .param("user", userId)
                .query((rs, n) -> new SessionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        ts(rs.getTimestamp(4)), ts(rs.getTimestamp(5)), ts(rs.getTimestamp(6)), rs.getString(7),
                        rs.getString(8)))
                .list();
    }

    private static Instant ts(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
