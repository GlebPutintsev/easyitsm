package ru.easyitsm.audit.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditResult;
import ru.easyitsm.identity.events.AccountLocked;
import ru.easyitsm.identity.events.GroupMembershipChanged;
import ru.easyitsm.identity.events.LoginFailed;
import ru.easyitsm.identity.events.LoginSucceeded;
import ru.easyitsm.identity.events.PasswordResetRequested;
import ru.easyitsm.identity.events.RoleGranted;
import ru.easyitsm.identity.events.RoleRevoked;
import ru.easyitsm.identity.events.SecurityFactorChanged;
import ru.easyitsm.identity.events.UserBlocked;
import ru.easyitsm.identity.events.UserCreated;
import ru.easyitsm.identity.events.UserUnblocked;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Вход, факторы, роли, группы и учётные записи из событий identity (SEC-29). Через outbox: если запись не
 * удалась, публикация остаётся незавершённой и повторяется при перезапуске.
 * <p>
 * Не {@code @ApplicationModuleListener}: его транзакция берёт соединение до установки арендатора события.
 * Ссылку сброса пароля и адрес почты из {@link PasswordResetRequested} в журнал не пишем.
 */
@Component
class IdentityEventsListener {

    private final AuditWriter writer;

    IdentityEventsListener(AuditWriter writer) {
        this.writer = writer;
    }

    @TransactionalEventListener
    void on(LoginSucceeded e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.AUTH, "auth.login")
                .actor(e.userId(), null).ip(e.ip()).object("user", e.userId()).detail("method", e.method()));
    }

    @TransactionalEventListener
    void on(LoginFailed e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.AUTH, "auth.login").result(AuditResult.FAILURE)
                .actor(e.userId(), e.login()).ip(e.ip()).object("user", e.userId())
                .detail("method", e.method()).detail("knownUser", e.userId() != null));
    }

    @TransactionalEventListener
    void on(AccountLocked e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.AUTH, "auth.account.locked").result(AuditResult.DENIED)
                .actor(e.userId(), null).ip(e.ip()).object("user", e.userId())
                .detail("lockedUntil", e.lockedUntil()));
    }

    @TransactionalEventListener
    void on(PasswordResetRequested e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.AUTH, "auth.password.reset_requested")
                .actor(e.userId(), null).object("user", e.userId()).detail("expiresAt", e.expiresAt()));
    }

    @TransactionalEventListener
    void on(SecurityFactorChanged e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.AUTH, "auth.factor." + e.change())
                .actor(e.actorId(), null).object("user", e.userId()));
    }

    @TransactionalEventListener
    void on(RoleGranted e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS, "access.role.grant")
                .actor(e.grantedBy(), null).object("role_grant", e.grantId())
                .detail("role", e.role()).detail("userId", e.userId()).detail("groupId", e.groupId())
                .detail("reason", e.reason()).detail("expiresAt", e.expiresAt()));
    }

    @TransactionalEventListener
    void on(RoleRevoked e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS, "access.role.revoke")
                .actor(e.revokedBy(), null).object("role_grant", e.grantId())
                .detail("role", e.role()).detail("userId", e.userId()).detail("groupId", e.groupId()));
    }

    @TransactionalEventListener
    void on(GroupMembershipChanged e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS,
                        e.added() ? "access.group.member_add" : "access.group.member_remove")
                .actor(e.actorId(), null).object("group", e.groupId()).detail("userId", e.userId()));
    }

    @TransactionalEventListener
    void on(UserCreated e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS, "access.user.create")
                .object("user", e.userId()).detail("login", e.login()).detail("source", e.source()));
    }

    @TransactionalEventListener
    void on(UserBlocked e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS, "access.user.block")
                .actor(e.actorId(), null).object("user", e.userId()).detail("reason", e.reason()));
    }

    @TransactionalEventListener
    void on(UserUnblocked e) {
        write(e.tenantId(), AuditEntry.of(AuditCategory.ACCESS, "access.user.unblock")
                .actor(e.actorId(), null).object("user", e.userId()));
    }

    private void write(UUID tenantId, AuditEntry entry) {
        TenantContext.runAs(tenantId, () -> writer.recordSeparately(entry));
    }
}
