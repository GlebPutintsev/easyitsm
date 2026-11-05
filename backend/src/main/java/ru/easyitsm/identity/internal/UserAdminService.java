package ru.easyitsm.identity.internal;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.events.SecurityFactorChanged;
import ru.easyitsm.identity.events.UserBlocked;
import ru.easyitsm.identity.events.UserCreated;
import ru.easyitsm.identity.events.UserUnblocked;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Пользователи в «Управлении» и при синхронизации с каталогом. */
@Service
class UserAdminService {

    private final UserRepository users;
    private final Credentials credentials;
    private final SessionStore sessions;
    private final PasswordPolicy passwordPolicy;
    private final Argon2PasswordEncoder hasher;
    private final ApplicationEventPublisher events;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    UserAdminService(UserRepository users, Credentials credentials, SessionStore sessions,
                     PasswordPolicy passwordPolicy, Argon2PasswordEncoder hasher, ApplicationEventPublisher events,
                     org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.users = users;
        this.credentials = credentials;
        this.sessions = sessions;
        this.passwordPolicy = passwordPolicy;
        this.hasher = hasher;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CursorPage<UserView> list(String q, String state, PageParams page) {
        KeysetCursor after = page.keyset();
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "\\%") + "%";
        List<UserAccount> rows = users.page(pattern, state, after == null ? null : after.sortValue(),
                after == null ? null : after.id(), page.limit() + 1);
        return CursorPage.of(rows, page.limit(), u -> new KeysetCursor(u.displayName().toLowerCase(), u.getId()))
                .map(UserAccount::toView);
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return find(id).toView();
    }

    @Transactional
    public UserView create(UserCommands.Create cmd) {
        validateTimezone(cmd.timezone());
        var user = new UserAccount(cmd.login().trim(), cmd.email(), cmd.displayName().trim(), "local", null);
        applyProfile(user, cmd.locale(), cmd.timezone(), cmd.organizationId(), cmd.departmentId(), cmd.managerId());
        String hash = null;
        if (cmd.password() != null && !cmd.password().isEmpty()) {
            passwordPolicy.check(cmd.password(), cmd.login(), cmd.email());
            hash = hasher.encode(cmd.password());
        }
        user = save(user);
        credentials.create(user.getId(), hash);
        events.publishEvent(new UserCreated(user.getTenantId(), user.getId(), user.login(), "local"));
        return user.toView();
    }

    @Transactional
    public UserView update(UUID id, String ifMatch, UserCommands.Update cmd) {
        UserAccount user = find(id);
        ETags.requireMatch(ifMatch, user.getVersion());
        validateTimezone(cmd.timezone());
        if (cmd.email() != null) {
            user.setEmail(cmd.email());
        }
        if (cmd.displayName() != null) {
            user.setDisplayName(cmd.displayName().trim());
        }
        applyProfile(user, cmd.locale(), cmd.timezone(), cmd.organizationId(), cmd.departmentId(), cmd.managerId());
        return save(user).toView();
    }

    @Transactional
    public UserView block(UUID id) {
        UUID actor = CurrentActor.userId().orElse(null);
        if (id.equals(actor)) {
            throw ApiException.badRequest("cannot_block_self", "Нельзя заблокировать себя");
        }
        UserAccount user = find(id);
        block(user, "admin", actor);
        return user.toView();
    }

    /** Блокирует пользователя и сразу завершает его сессии (ACC-10). */
    void block(UserAccount user, String reason, UUID actor) {
        if (!user.active()) {
            return;
        }
        user.block(reason);
        users.save(user);
        sessions.revokeAll(user.getId(), null, "blocked");
        events.publishEvent(new UserBlocked(user.getTenantId(), user.getId(), reason, actor));
    }

    @Transactional
    public UserView unblock(UUID id) {
        UserAccount user = find(id);
        if ("source".equals(user.blockedReason())) {
            throw ApiException.conflict("blocked_in_source",
                    "Учётная запись отключена в каталоге. Включите её в источнике");
        }
        unblock(user, CurrentActor.userId().orElse(null));
        credentials.unlock(id);
        return user.toView();
    }

    void unblock(UserAccount user, UUID actor) {
        if (user.active()) {
            return;
        }
        user.unblock();
        users.save(user);
        events.publishEvent(new UserUnblocked(user.getTenantId(), user.getId(), actor));
    }

    @Transactional
    public int revokeSessions(UUID id) {
        find(id);
        return sessions.revokeAll(id, null, "admin");
    }

    /**
     * Сброс MFA администратором (10.16 ТЗ, угроза T-01). Требует повторного входа; владельцу уходит оповещение,
     * сессии завершаются, при следующем входе MFA настраивается заново.
     */
    @Transactional
    public void resetMfa(UUID id) {
        UUID actor = CurrentActor.userId().orElse(null);
        if (id.equals(actor)) {
            throw ApiException.forbidden();
        }
        UserAccount user = find(id);
        credentials.require(id);
        credentials.clearTotp(id);
        credentials.clearRecoveryCodes(id);
        jdbc.sql("delete from webauthn_credential where user_id = :user").param("user", id).update();
        jdbc.sql("delete from trusted_device where user_id = :user").param("user", id).update();
        sessions.revokeAll(id, null, "mfa_reset");
        events.publishEvent(new SecurityFactorChanged(user.getTenantId(), id, "mfa_reset", actor));
    }

    @Transactional
    public void setPassword(UUID id, String password) {
        UserAccount user = find(id);
        if (!user.local()) {
            throw ApiException.conflict("not_local_account", "Пароль этой учётной записи задаётся в каталоге или IdP");
        }
        passwordPolicy.check(password, user.login(), user.email());
        credentials.setPassword(id, hasher.encode(password));
        sessions.revokeAll(id, null, "password_reset");
        events.publishEvent(new SecurityFactorChanged(user.getTenantId(), id, "password_reset",
                CurrentActor.userId().orElse(null)));
    }

    /**
     * Создаёт или обновляет пользователя из внешнего источника (LDAP, OIDC). Пользователь ищется по неизменяемому
     * идентификатору; если его ещё нет, но есть локальный пользователь с тем же логином, источник не меняется —
     * это решение администратора, иначе внешний IdP мог бы захватить локальную учётную запись.
     */
    UserAccount provision(String source, String externalId, String login, String email, String displayName) {
        Optional<UserAccount> existing = users.findByExternalId(source, externalId);
        if (existing.isPresent()) {
            UserAccount user = existing.get();
            boolean changed = false;
            if (login != null && !login.equals(user.login())) {
                user.setLogin(login);
                changed = true;
            }
            if (email != null && !email.equalsIgnoreCase(String.valueOf(user.email()))) {
                user.setEmail(email);
                changed = true;
            }
            if (displayName != null && !displayName.equals(user.displayName())) {
                user.setDisplayName(displayName);
                changed = true;
            }
            return changed ? save(user) : user;
        }
        if (users.findByLoginOrEmail(login).isPresent()
                || (email != null && users.findByLoginOrEmail(email).isPresent())) {
            throw ApiException.conflict("login_taken",
                    "Пользователь с таким логином или почтой уже есть и создан другим способом");
        }
        UserAccount user = save(new UserAccount(login, email, displayName == null ? login : displayName, source,
                externalId));
        credentials.create(user.getId(), null);
        events.publishEvent(new UserCreated(TenantContext.require(), user.getId(), login, source));
        return user;
    }

    UserAccount find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь"));
    }

    private UserAccount save(UserAccount user) {
        return Duplicates.guard(() -> users.save(user), "login_taken", "Логин или почта уже заняты");
    }

    private static void applyProfile(UserAccount user, String locale, String timezone, UUID organizationId,
                                     UUID departmentId, UUID managerId) {
        if (locale != null) {
            user.setLocale(locale);
        }
        if (timezone != null) {
            user.setTimezone(timezone);
        }
        if (organizationId != null) {
            user.setOrganizationId(organizationId);
        }
        if (departmentId != null) {
            user.setDepartmentId(departmentId);
        }
        if (managerId != null) {
            if (managerId.equals(user.getId())) {
                throw ApiException.badRequest("invalid_manager", "Пользователь не может быть своим руководителем");
            }
            user.setManagerId(managerId);
        }
    }

    private static void validateTimezone(String timezone) {
        if (timezone == null) {
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс");
        }
    }
}
