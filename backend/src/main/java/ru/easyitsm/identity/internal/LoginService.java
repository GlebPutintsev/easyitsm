package ru.easyitsm.identity.internal;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.identity.events.AccountLocked;
import ru.easyitsm.identity.events.LoginFailed;
import ru.easyitsm.identity.events.LoginSucceeded;
import ru.easyitsm.identity.events.PasswordResetRequested;
import ru.easyitsm.identity.events.SecurityFactorChanged;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Сценарии входа (10.3 ТЗ): пароль, второй фактор, обязательная настройка MFA, passkey, повторная аутентификация,
 * выход, смена и сброс пароля. Все методы вызываются в контексте арендатора.
 * <p>
 * Неудачная попытка не откатывает транзакцию ({@code noRollbackFor}): счётчик неудач и журнал должны сохраниться.
 */
@Service
class LoginService {

    static final String OK = "ok";
    static final String MFA_REQUIRED = "mfa_required";
    static final String MFA_SETUP_REQUIRED = "mfa_setup_required";

    private final UserRepository users;
    private final Credentials credentials;
    private final SessionStore sessions;
    private final MfaService mfa;
    private final WebAuthnService webauthn;
    private final Directory directory;
    private final LdapClient ldap;
    private final UserAdminService userAdmin;
    private final PasswordPolicy passwordPolicy;
    private final Argon2PasswordEncoder hasher;
    private final LoginHistory history;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final IdentityProperties props;
    /** Хеш для выравнивания времени ответа, когда пользователь не найден. */
    private final String dummyHash;

    LoginService(UserRepository users, Credentials credentials, SessionStore sessions, MfaService mfa,
                 WebAuthnService webauthn, Directory directory, LdapClient ldap, UserAdminService userAdmin,
                 PasswordPolicy passwordPolicy, Argon2PasswordEncoder hasher, LoginHistory history,
                 ApplicationEventPublisher events, JdbcClient jdbc, IdentityProperties props) {
        this.users = users;
        this.credentials = credentials;
        this.sessions = sessions;
        this.mfa = mfa;
        this.webauthn = webauthn;
        this.directory = directory;
        this.ldap = ldap;
        this.userAdmin = userAdmin;
        this.passwordPolicy = passwordPolicy;
        this.hasher = hasher;
        this.history = history;
        this.events = events;
        this.jdbc = jdbc;
        this.props = props;
        this.dummyHash = hasher.encode(Secrets.newToken());
    }

    /**
     * Результат шага входа.
     *
     * @param status        ok, mfa_required, mfa_setup_required
     * @param methods       доступные вторые факторы
     * @param session       новая сессия: её токен уходит в куку
     * @param deviceToken   токен «запомненного» устройства, если пользователь попросил
     * @param recoveryCodes резервные коды, если они только что выпущены
     */
    record Issued(String status, List<String> methods, SessionStore.Created session, String deviceToken,
                  List<String> recoveryCodes) {
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public Issued passwordLogin(String login, String password, RequestInfo info, String deviceToken) {
        String normalized = login == null ? "" : login.trim();
        Optional<UserAccount> found = users.findByLoginOrEmail(normalized);
        if (found.isEmpty() && ldap.enabled()) {
            var ldapUser = ldap.authenticate(normalized, password);
            if (ldapUser.isPresent()) {
                var u = ldapUser.get();
                found = Optional.of(userAdmin.provision("ldap", u.externalId(), u.login(), u.email(), u.displayName()));
                return afterPassword(found.get(), info, deviceToken);
            }
        }
        if (found.isEmpty()) {
            hasher.matches(password == null ? "" : password, dummyHash);
            history.record(null, normalized, "unknown_user", "password", info);
            events.publishEvent(new LoginFailed(TenantContext.require(), null, normalized, "password", info.ip()));
            throw new AuthFailure(1);
        }
        UserAccount user = found.get();
        var cred = credentials.require(user.getId());
        if (cred.locked(Instant.now())) {
            hasher.matches(password == null ? "" : password, dummyHash);
            history.record(user.getId(), normalized, "locked", "password", info);
            throw new AuthFailure(props.lockout().maxFailures());
        }
        boolean ok = switch (user.source()) {
            case "local" -> props.password().localLoginEnabled() && cred.passwordHash() != null && password != null
                    && hasher.matches(password, cred.passwordHash());
            case "ldap" -> ldap.authenticate(user.login(), password).isPresent();
            default -> {
                hasher.matches(password == null ? "" : password, dummyHash);
                yield false;
            }
        };
        if (!ok) {
            throw fail(user, normalized, "password", info);
        }
        if (!user.active()) {
            history.record(user.getId(), normalized, "blocked", "password", info);
            throw new AuthFailure(1);
        }
        credentials.resetFailures(user.getId());
        if (user.local() && hasher.upgradeEncoding(cred.passwordHash())) {
            credentials.rehash(user.getId(), hasher.encode(password));
        }
        return afterPassword(user, info, deviceToken);
    }

    private Issued afterPassword(UserAccount user, RequestInfo info, String deviceToken) {
        List<String> factors = mfa.factors(user.getId());
        if (!factors.isEmpty()) {
            if (mfa.isTrustedDevice(user.getId(), deviceToken)) {
                return activate(user, "password+device", info, null, List.of());
            }
            return pending(user, SessionStore.MFA, "password", factors, info);
        }
        Set<Role> roles = directory.rolesOf(user.getId());
        if (mfa.mfaRequired(roles)) {
            return pending(user, SessionStore.MFA_SETUP, "password", List.of(MfaService.TOTP, MfaService.WEBAUTHN),
                    info);
        }
        return activate(user, "password", info, null, List.of());
    }

    /** Второй фактор: код приложения или резервный код. */
    @Transactional(noRollbackFor = AuthFailure.class)
    public Issued completeMfa(SessionAuthenticationFilter.PendingSession pending, String method, String code,
                              boolean rememberDevice, RequestInfo info) {
        BooleanSupplier check = switch (method) {
            case MfaService.TOTP -> () -> mfa.verifyTotp(pending.userId(), code);
            case MfaService.RECOVERY -> () -> mfa.verifyRecovery(pending.userId(), code);
            default -> throw ApiException.badRequest("unknown_method", "Неизвестный способ подтверждения");
        };
        return completeMfa(pending, method, check, rememberDevice, info);
    }

    /** Второй фактор: ключ WebAuthn. */
    @Transactional(noRollbackFor = AuthFailure.class)
    public Issued completeMfaWithKey(SessionAuthenticationFilter.PendingSession pending, UUID ceremonyId,
                                     String responseJson, boolean rememberDevice, RequestInfo info) {
        BooleanSupplier check = () -> {
            try {
                webauthn.authenticate(ceremonyId, responseJson, pending.userId());
                return true;
            } catch (AuthFailure e) {
                return false;
            }
        };
        return completeMfa(pending, MfaService.WEBAUTHN, check, rememberDevice, info);
    }

    private Issued completeMfa(SessionAuthenticationFilter.PendingSession pending, String method,
                               BooleanSupplier check, boolean rememberDevice, RequestInfo info) {
        requireState(pending, SessionStore.MFA);
        UserAccount user = userAdmin.find(pending.userId());
        if (credentials.require(user.getId()).locked(Instant.now())) {
            sessions.revoke(pending.sessionId(), "locked");
            throw AuthFailure.invalidCode(props.lockout().maxFailures());
        }
        if (!check.getAsBoolean()) {
            AuthFailure failure = fail(user, user.login(), method, info);
            if (failure.failures() >= props.lockout().maxFailures()) {
                sessions.revoke(pending.sessionId(), "locked");
            }
            throw AuthFailure.invalidCode(failure.failures());
        }
        credentials.resetFailures(user.getId());
        sessions.revoke(pending.sessionId(), "mfa_passed");
        String device = rememberDevice && mfa.rememberDeviceAllowed() ? mfa.rememberDevice(user.getId(), info) : null;
        return activate(user, "password+" + method, info, device, List.of());
    }

    @Transactional
    public MfaService.TotpSetup startTotpSetup(SessionAuthenticationFilter.PendingSession pending) {
        requireState(pending, SessionStore.MFA_SETUP);
        return mfa.startTotp(pending.userId(), pending.login());
    }

    /** Обязательная настройка MFA при входе: первый код из приложения завершает вход. */
    @Transactional(noRollbackFor = AuthFailure.class)
    public Issued confirmTotpSetup(SessionAuthenticationFilter.PendingSession pending, String code, RequestInfo info) {
        requireState(pending, SessionStore.MFA_SETUP);
        UserAccount user = userAdmin.find(pending.userId());
        List<String> codes = mfa.confirmTotp(user.getId(), code);
        sessions.revoke(pending.sessionId(), "mfa_enrolled");
        events.publishEvent(new SecurityFactorChanged(TenantContext.require(), user.getId(), "totp_enabled",
                user.getId()));
        return activate(user, "password+totp", info, null, codes);
    }

    @Transactional
    public WebAuthnService.Options keySetupOptions(SessionAuthenticationFilter.PendingSession pending) {
        requireState(pending, SessionStore.MFA_SETUP);
        UserAccount user = userAdmin.find(pending.userId());
        return webauthn.registrationOptions(user.getId(), user.login(), user.displayName());
    }

    @Transactional
    public Issued confirmKeySetup(SessionAuthenticationFilter.PendingSession pending, UUID ceremonyId,
                                  String responseJson, String name, RequestInfo info) {
        requireState(pending, SessionStore.MFA_SETUP);
        UserAccount user = userAdmin.find(pending.userId());
        webauthn.register(user.getId(), ceremonyId, responseJson, name);
        List<String> codes = credentials.recoveryCodesLeft(user.getId()) == 0
                ? credentials.replaceRecoveryCodes(user.getId()) : List.of();
        sessions.revoke(pending.sessionId(), "mfa_enrolled");
        events.publishEvent(new SecurityFactorChanged(TenantContext.require(), user.getId(), "webauthn_added",
                user.getId()));
        return activate(user, "password+webauthn", info, null, codes);
    }

    @Transactional
    public WebAuthnService.Options mfaKeyOptions(SessionAuthenticationFilter.PendingSession pending) {
        requireState(pending, SessionStore.MFA);
        return webauthn.authenticationOptions(pending.userId());
    }

    @Transactional
    public WebAuthnService.Options keyOptionsFor(UUID userId) {
        return webauthn.authenticationOptions(userId);
    }

    @Transactional
    public WebAuthnService.Options passkeyOptions() {
        return webauthn.authenticationOptions(null);
    }

    /** Вход по passkey без пароля: ключ с проверкой пользователя сам является двумя факторами. */
    @Transactional(noRollbackFor = AuthFailure.class)
    public Issued passkeyLogin(UUID ceremonyId, String responseJson, RequestInfo info) {
        UUID userId;
        try {
            userId = webauthn.authenticate(ceremonyId, responseJson, null);
        } catch (AuthFailure e) {
            history.record(null, "", "failure", "passkey", info);
            throw e;
        }
        UserAccount user = userAdmin.find(userId);
        if (!user.active() || credentials.require(userId).locked(Instant.now())) {
            history.record(userId, user.login(), "blocked", "passkey", info);
            throw new AuthFailure(1);
        }
        return activate(user, "passkey", info, null, List.of());
    }

    /** Вход через внешний IdP завершён: MFA проверил IdP или она будет запрошена здесь. */
    @Transactional
    public Issued ssoLogin(UserAccount user, String method, RequestInfo info) {
        if (!user.active()) {
            history.record(user.getId(), user.login(), "blocked", method, info);
            throw new AuthFailure(1);
        }
        if (props.mfa().trustSsoMfa()) {
            return activate(user, method, info, null, List.of());
        }
        return afterPassword(user, info, null);
    }

    /** Повторная аутентификация в активной сессии (SEC-08): пароль, код приложения или ключ. */
    @Transactional(noRollbackFor = AuthFailure.class)
    public void reauthenticate(UUID userId, SessionInfo session, String password, String code, UUID ceremonyId,
                               String responseJson, RequestInfo info) {
        UserAccount user = userAdmin.find(userId);
        if (credentials.require(userId).locked(Instant.now())) {
            throw new AuthFailure(props.lockout().maxFailures());
        }
        boolean ok;
        String method;
        if (ceremonyId != null) {
            method = MfaService.WEBAUTHN;
            try {
                webauthn.authenticate(ceremonyId, responseJson, userId);
                ok = true;
            } catch (AuthFailure e) {
                ok = false;
            }
        } else if (code != null && !code.isBlank()) {
            method = MfaService.TOTP;
            ok = mfa.verifyTotp(userId, code);
        } else {
            method = "password";
            var cred = credentials.require(userId);
            ok = password != null && switch (user.source()) {
                case "local" -> cred.passwordHash() != null && hasher.matches(password, cred.passwordHash());
                case "ldap" -> ldap.authenticate(user.login(), password).isPresent();
                default -> false;
            };
        }
        if (!ok) {
            throw fail(user, user.login(), "reauth_" + method, info);
        }
        credentials.resetFailures(userId);
        sessions.markReauthenticated(session.sessionId());
    }

    @Transactional
    public void logout(UUID sessionId) {
        sessions.revoke(sessionId, "logout");
    }

    @Transactional
    public void changePassword(UUID userId, UUID currentSessionId, String current, String next) {
        UserAccount user = userAdmin.find(userId);
        if (!user.local()) {
            throw ApiException.conflict("not_local_account", "Пароль этой учётной записи задаётся в каталоге или IdP");
        }
        var cred = credentials.require(userId);
        if (cred.passwordHash() != null && (current == null || !hasher.matches(current, cred.passwordHash()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_current_password", "Текущий пароль указан неверно");
        }
        passwordPolicy.check(next, user.login(), user.email());
        credentials.setPassword(userId, hasher.encode(next));
        sessions.revokeAll(userId, currentSessionId, "password_changed");
        events.publishEvent(new SecurityFactorChanged(TenantContext.require(), userId, "password_changed", userId));
    }

    /**
     * «Забыли пароль?»: ответ всегда одинаковый (10.3 ТЗ). Письмо уходит только активному локальному пользователю
     * с почтой. Токен содержит арендатора, чтобы ссылка работала и в облачном режиме.
     */
    @Transactional
    public void requestPasswordReset(String login, RequestInfo info) {
        Optional<UserAccount> found = login == null ? Optional.empty() : users.findByLoginOrEmail(login.trim());
        if (found.isEmpty() || !found.get().local() || !found.get().active() || found.get().email() == null
                || !props.password().localLoginEnabled()) {
            return;
        }
        UserAccount user = found.get();
        UUID tenant = TenantContext.require();
        String token = tenantPrefix(tenant) + "." + Secrets.newToken();
        Instant expires = Instant.now().plus(props.password().resetTokenTtl());
        jdbc.sql("update password_reset set used_at = now() where user_id = :user and used_at is null")
                .param("user", user.getId()).update();
        jdbc.sql("""
                insert into password_reset (id, tenant_id, user_id, token_hash, expires_at)
                values (:id, :tenant, :user, :hash, :expires)""")
                .param("id", Ids.newId()).param("tenant", tenant).param("user", user.getId())
                .param("hash", Secrets.sha256(token)).param("expires", Timestamp.from(expires))
                .update();
        String url = props.publicUrl().replaceAll("/+$", "") + "/reset-password?token=" + token;
        events.publishEvent(new PasswordResetRequested(tenant, user.getId(), user.email(), url, expires));
        history.record(user.getId(), user.login(), "reset_requested", "password", info);
    }

    @Transactional
    public void resetPassword(String token, String password) {
        var row = jdbc.sql("""
                select user_id from password_reset
                 where token_hash = :hash and used_at is null and expires_at > now()""")
                .param("hash", Secrets.sha256(token))
                .query(UUID.class).optional()
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "Ссылка недействительна или устарела"));
        UserAccount user = userAdmin.find(row);
        passwordPolicy.check(password, user.login(), user.email());
        jdbc.sql("update password_reset set used_at = now() where token_hash = :hash")
                .param("hash", Secrets.sha256(token)).update();
        credentials.setPassword(user.getId(), hasher.encode(password));
        sessions.revokeAll(user.getId(), null, "password_reset");
        events.publishEvent(new SecurityFactorChanged(TenantContext.require(), user.getId(), "password_reset",
                user.getId()));
    }

    /** Арендатор из токена сброса пароля, без обращения к базе. */
    static Optional<UUID> tenantOfResetToken(String token) {
        if (token == null || token.indexOf('.') != 22) {
            return Optional.empty();
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(Base64.getUrlDecoder().decode(token.substring(0, 22)));
            return Optional.of(new UUID(b.getLong(), b.getLong()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String tenantPrefix(UUID tenant) {
        byte[] bytes = ByteBuffer.allocate(16).putLong(tenant.getMostSignificantBits())
                .putLong(tenant.getLeastSignificantBits()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Issued pending(UserAccount user, String state, String method, List<String> methods, RequestInfo info) {
        var session = sessions.create(user.getId(), state, method,
                Instant.now().plus(props.session().pendingTimeout()), false, info);
        return new Issued(SessionStore.MFA.equals(state) ? MFA_REQUIRED : MFA_SETUP_REQUIRED, methods, session, null,
                List.of());
    }

    private Issued activate(UserAccount user, String method, RequestInfo info, String deviceToken,
                            List<String> recoveryCodes) {
        // Свежий вход считается повторной аутентификацией: сразу после входа подтверждать его не нужно.
        var session = sessions.create(user.getId(), SessionStore.ACTIVE, method,
                Instant.now().plus(props.session().absoluteTimeout()), true, info);
        users.touchLastLogin(user.getId());
        history.record(user.getId(), user.login(), "success", method, info);
        events.publishEvent(new LoginSucceeded(TenantContext.require(), user.getId(), session.id(), method, info.ip()));
        return new Issued(OK, List.of(), session, deviceToken, recoveryCodes);
    }

    private AuthFailure fail(UserAccount user, String login, String method, RequestInfo info) {
        Instant lockUntil = Instant.now().plus(props.lockout().lockDuration());
        int failures = credentials.registerFailure(user.getId(), props.lockout().maxFailures(), lockUntil);
        history.record(user.getId(), login, "failure", method, info);
        events.publishEvent(new LoginFailed(TenantContext.require(), user.getId(), login, method, info.ip()));
        if (failures == props.lockout().maxFailures()) {
            events.publishEvent(new AccountLocked(TenantContext.require(), user.getId(), lockUntil, info.ip()));
        }
        return new AuthFailure(failures);
    }

    private static void requireState(SessionAuthenticationFilter.PendingSession pending, String state) {
        if (pending == null || !state.equals(pending.state())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "login_required", "Начните вход заново");
        }
    }
}
