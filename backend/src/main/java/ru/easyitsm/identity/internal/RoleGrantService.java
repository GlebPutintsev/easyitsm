package ru.easyitsm.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.identity.events.RoleGranted;
import ru.easyitsm.identity.events.RoleRevoked;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Выдача и отзыв ролей (раздел 3 ТЗ). Роли назначает SEC с повторной аутентификацией (SEC-08),
 * выдать роль себе нельзя ни напрямую, ни через свою группу (ACC-05).
 */
@Service
class RoleGrantService {

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final GroupRepository groups;
    private final ApplicationEventPublisher events;

    RoleGrantService(JdbcClient jdbc, UserRepository users, GroupRepository groups, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.users = users;
        this.groups = groups;
        this.events = events;
    }

    record Grant(@NotNull String role, UUID userId, UUID groupId, @NotBlank @Size(max = 1000) String reason,
                 Instant expiresAt) {
    }

    record GrantView(UUID id, String role, UUID userId, UUID groupId, String reason, UUID grantedBy, Instant grantedAt,
                     Instant expiresAt, Instant revokedAt, UUID revokedBy) {
    }

    record RoleView(String code, boolean privileged, boolean requiresMfa) {
    }

    List<RoleView> catalog() {
        return java.util.Arrays.stream(Role.values()).map(r -> new RoleView(r.name(), r.privileged(), r.requiresMfa()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GrantView> list(UUID userId, UUID groupId, boolean includeRevoked) {
        return jdbc.sql("""
                select id, role, user_id, group_id, reason, granted_by, granted_at, expires_at, revoked_at, revoked_by
                  from role_grant
                 where (cast(:user as uuid) is null or user_id = :user
                        or group_id in (select group_id from group_member where user_id = :user))
                   and (cast(:group as uuid) is null or group_id = :group)
                   and (:includeRevoked or (revoked_at is null and (expires_at is null or expires_at > now())))
                 order by granted_at desc, id
                 limit 500""")
                .param("user", userId).param("group", groupId).param("includeRevoked", includeRevoked)
                .query((rs, n) -> new GrantView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, UUID.class), ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)),
                        ts(rs.getTimestamp(9)), rs.getObject(10, UUID.class)))
                .list();
    }

    @Transactional
    public GrantView grant(Grant cmd) {
        Role role = Role.parse(cmd.role()).orElseThrow(() -> ApiException.badRequest("unknown_role", "Неизвестная роль"));
        if ((cmd.userId() == null) == (cmd.groupId() == null)) {
            throw ApiException.badRequest("invalid_subject", "Укажите пользователя или группу");
        }
        if (cmd.expiresAt() != null && !cmd.expiresAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("invalid_expiry", "Срок должен быть в будущем");
        }
        UUID actor = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        if (cmd.userId() != null) {
            if (cmd.userId().equals(actor)) {
                throw selfGrant();
            }
            users.findById(cmd.userId()).orElseThrow(() -> ApiException.notFound("Пользователь"));
        } else {
            groups.findById(cmd.groupId()).orElseThrow(() -> ApiException.notFound("Группа"));
            boolean actorInGroup = jdbc.sql("select exists (select 1 from group_member where group_id = :g and user_id = :u)")
                    .param("g", cmd.groupId()).param("u", actor).query(Boolean.class).single();
            if (actorInGroup) {
                throw selfGrant();
            }
        }
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into role_grant (id, tenant_id, role, user_id, group_id, reason, granted_by, expires_at)
                values (:id, :tenant, :role, :user, :group, :reason, :actor, :expires)""")
                .param("id", id).param("tenant", TenantContext.require()).param("role", role.name())
                .param("user", cmd.userId()).param("group", cmd.groupId()).param("reason", cmd.reason().trim())
                .param("actor", actor).param("expires", cmd.expiresAt() == null ? null : Timestamp.from(cmd.expiresAt()))
                .update();
        events.publishEvent(new RoleGranted(TenantContext.require(), id, role.name(), cmd.userId(), cmd.groupId(),
                actor, cmd.reason().trim(), cmd.expiresAt()));
        return find(id);
    }

    @Transactional
    public GrantView revoke(UUID id) {
        GrantView grant = find(id);
        if (grant.revokedAt() != null) {
            return grant;
        }
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("update role_grant set revoked_at = now(), revoked_by = :actor where id = :id and revoked_at is null")
                .param("actor", actor).param("id", id).update();
        events.publishEvent(new RoleRevoked(TenantContext.require(), id, grant.role(), grant.userId(), grant.groupId(),
                actor));
        return find(id);
    }

    private GrantView find(UUID id) {
        return jdbc.sql("""
                select id, role, user_id, group_id, reason, granted_by, granted_at, expires_at, revoked_at, revoked_by
                  from role_grant where id = :id""")
                .param("id", id)
                .query((rs, n) -> new GrantView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, UUID.class), ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)),
                        ts(rs.getTimestamp(9)), rs.getObject(10, UUID.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Выдача роли"));
    }

    private static ApiException selfGrant() {
        return new ApiException(HttpStatus.FORBIDDEN, "self_grant_forbidden", "Нельзя выдать права себе");
    }

    private static Instant ts(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
