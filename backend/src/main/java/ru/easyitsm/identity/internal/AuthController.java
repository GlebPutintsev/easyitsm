package ru.easyitsm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.databind.JsonNode;

/**
 * Эндпоинты входа (10.3 ТЗ). Доступны без входа; изменяющие запросы защищены CSRF-токеном.
 * Ответы об ошибках входа одинаковые и не раскрывают, существует ли логин.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginService login;
    private final LoginThrottle throttle;
    private final SessionCookies cookies;
    private final Tenancy tenancy;
    private final PasswordPolicy passwordPolicy;
    private final IdentityProperties props;
    private final LdapClient ldap;
    private final ObjectProvider<InMemoryClientRegistrationRepository> sso;

    AuthController(LoginService login, LoginThrottle throttle, SessionCookies cookies, Tenancy tenancy,
                   PasswordPolicy passwordPolicy, IdentityProperties props, LdapClient ldap,
                   ObjectProvider<InMemoryClientRegistrationRepository> sso) {
        this.login = login;
        this.throttle = throttle;
        this.cookies = cookies;
        this.tenancy = tenancy;
        this.passwordPolicy = passwordPolicy;
        this.props = props;
        this.ldap = ldap;
        this.sso = sso;
    }

    record SsoProvider(String id, String name) {
    }

    record Config(boolean passwordLogin, boolean multiTenant, List<SsoProvider> sso, boolean passkeys,
                  int rememberDeviceDays, int passwordMinLength, boolean authenticated, String pendingStep) {
    }

    record LoginRequest(@NotBlank @Size(max = 320) String login, @NotNull @Size(max = 1024) String password,
                        @Size(max = 63) String tenant) {
    }

    record CodeRequest(@NotBlank @Size(max = 32) String method, @NotBlank @Size(max = 32) String code,
                       Boolean rememberDevice) {

        boolean remember() {
            return Boolean.TRUE.equals(rememberDevice);
        }
    }



    record KeyRequest(@NotNull UUID ceremonyId, @NotNull JsonNode credential, Boolean rememberDevice,
                      @Size(max = 100) String name, @Size(max = 63) String tenant) {

        boolean remember() {
            return Boolean.TRUE.equals(rememberDevice);
        }
    }

    record ReauthRequest(@Size(max = 1024) String password, @Size(max = 32) String code, UUID ceremonyId,
                         JsonNode credential) {
    }

    record ForgotRequest(@NotBlank @Size(max = 320) String login, @Size(max = 63) String tenant) {
    }

    record ResetRequest(@NotBlank @Size(max = 256) String token, @NotBlank @Size(max = 1024) String password) {
    }

    record TotpCodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    record TenantRequest(@Size(max = 63) String tenant) {
    }

    record Result(String status, List<String> methods, List<String> recoveryCodes) {
    }

    /** Что показать на экране входа. Выдаёт и CSRF-куку для следующего POST. */
    @GetMapping("/config")
    Config config(HttpServletRequest request) {
        List<SsoProvider> providers = new java.util.ArrayList<>();
        InMemoryClientRegistrationRepository repo = sso.getIfAvailable();
        if (repo != null) {
            for (ClientRegistration r : repo) {
                providers.add(new SsoProvider(r.getRegistrationId(), r.getClientName()));
            }
        }
        var pending = pending(request);
        return new Config(props.password().localLoginEnabled() || ldap.enabled(), tenancy.multi(), providers, true,
                props.mfa().rememberDeviceDays(), passwordPolicy.minLength(), CurrentActor.get().isPresent(),
                pending.map(SessionAuthenticationFilter.PendingSession::state).orElse(null));
    }

    @PostMapping("/login")
    Result login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        UUID tenant = tenancy.resolve(req.tenant()).orElseThrow(() -> new AuthFailure(1));
        String device = cookies.readDevice(request).orElse(null);
        var issued = guarded(info, () -> Tenancy.in(tenant,
                () -> login.passwordLogin(req.login(), req.password(), info, device)));
        endCurrentSession(request);
        return respond(issued, response);
    }

    @PostMapping("/mfa")
    Result mfa(@Valid @RequestBody CodeRequest req, HttpServletRequest request, HttpServletResponse response) {
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        var p = requirePending(request);
        var issued = guarded(info, () -> Tenancy.in(p.tenantId(),
                () -> login.completeMfa(p, req.method(), req.code(), req.remember(), info)));
        return respond(issued, response);
    }

    @PostMapping("/mfa/webauthn/options")
    WebAuthnService.Options mfaKeyOptions(HttpServletRequest request) {
        var p = requirePending(request);
        return Tenancy.in(p.tenantId(), () -> login.mfaKeyOptions(p));
    }

    @PostMapping("/mfa/webauthn")
    Result mfaKey(@Valid @RequestBody KeyRequest req, HttpServletRequest request, HttpServletResponse response) {
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        var p = requirePending(request);
        var issued = guarded(info, () -> Tenancy.in(p.tenantId(), () -> login.completeMfaWithKey(p, req.ceremonyId(),
                req.credential().toString(), req.remember(), info)));
        return respond(issued, response);
    }

    /** Обязательная настройка MFA: секрет для приложения. */
    @PostMapping("/mfa/setup/totp")
    MfaService.TotpSetup setupTotp(HttpServletRequest request) {
        var p = requirePending(request);
        return Tenancy.in(p.tenantId(), () -> login.startTotpSetup(p));
    }

    @PostMapping("/mfa/setup/totp/confirm")
    Result confirmTotp(@Valid @RequestBody TotpCodeRequest req, HttpServletRequest request,
                       HttpServletResponse response) {
        var p = requirePending(request);
        RequestInfo info = RequestInfo.of(request);
        return respond(Tenancy.in(p.tenantId(), () -> login.confirmTotpSetup(p, req.code(), info)), response);
    }

    @PostMapping("/mfa/setup/webauthn/options")
    WebAuthnService.Options setupKeyOptions(HttpServletRequest request) {
        var p = requirePending(request);
        return Tenancy.in(p.tenantId(), () -> login.keySetupOptions(p));
    }

    @PostMapping("/mfa/setup/webauthn")
    Result confirmKey(@Valid @RequestBody KeyRequest req, HttpServletRequest request, HttpServletResponse response) {
        var p = requirePending(request);
        RequestInfo info = RequestInfo.of(request);
        return respond(Tenancy.in(p.tenantId(), () -> login.confirmKeySetup(p, req.ceremonyId(),
                req.credential().toString(), req.name(), info)), response);
    }

    /** «Войти с ключом»: passkey без логина и пароля. */
    @PostMapping("/passkey/options")
    WebAuthnService.Options passkeyOptions(@RequestBody(required = false) TenantRequest req) {
        UUID tenant = tenancy.resolve(req == null ? null : req.tenant())
                .orElseThrow(() -> ApiException.notFound("Организация"));
        return Tenancy.in(tenant, login::passkeyOptions);
    }

    @PostMapping("/passkey")
    Result passkey(@Valid @RequestBody KeyRequest req, HttpServletRequest request, HttpServletResponse response) {
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        UUID tenant = tenancy.resolve(req.tenant()).orElseThrow(() -> new AuthFailure(1));
        var issued = guarded(info, () -> Tenancy.in(tenant,
                () -> login.passkeyLogin(req.ceremonyId(), req.credential().toString(), info)));
        endCurrentSession(request);
        return respond(issued, response);
    }

    /** Повторная аутентификация перед опасным действием (SEC-08). */
    @PostMapping("/reauth")
    ResponseEntity<Void> reauth(@RequestBody ReauthRequest req, HttpServletRequest request) {
        var actor = CurrentActor.get().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "Нужен вход"));
        var session = SessionInfo.current().orElseThrow(() -> ApiException.badRequest("no_session",
                "Повторная аутентификация доступна только в сессии"));
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        guarded(info, () -> {
            login.reauthenticate(actor.userId(), session, req.password(), req.code(), req.ceremonyId(),
                    req.credential() == null ? null : req.credential().toString(), info);
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reauth/webauthn/options")
    WebAuthnService.Options reauthKeyOptions() {
        var actor = CurrentActor.get().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "Нужен вход"));
        return login.keyOptionsFor(actor.userId());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        endCurrentSession(request);
        cookies.clearSession(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/forgot")
    ResponseEntity<Void> forgot(@Valid @RequestBody ForgotRequest req, HttpServletRequest request) {
        RequestInfo info = RequestInfo.of(request);
        throttle.check(info.ip());
        tenancy.resolve(req.tenant()).ifPresent(tenant ->
                Tenancy.run(tenant, () -> login.requestPasswordReset(req.login(), info)));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    ResponseEntity<Void> reset(@Valid @RequestBody ResetRequest req) {
        UUID tenant = LoginService.tenantOfResetToken(req.token())
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "Ссылка недействительна или устарела"));
        Tenancy.run(tenant, () -> login.resetPassword(req.token(), req.password()));
        return ResponseEntity.noContent().build();
    }

    private <T> T guarded(RequestInfo info, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (AuthFailure e) {
            throttle.recordFailure(info.ip());
            throttle.sleepAfterFailure(e.failures());
            throw e;
        }
    }

    private Result respond(LoginService.Issued issued, HttpServletResponse response) {
        cookies.writeSession(response, issued.session().token(), issued.session().expiresAt());
        if (issued.deviceToken() != null) {
            cookies.writeDevice(response, issued.deviceToken(),
                    java.time.Duration.ofDays(props.mfa().rememberDeviceDays()));
        }
        return new Result(issued.status(), issued.methods(),
                issued.recoveryCodes().isEmpty() ? null : issued.recoveryCodes());
    }

    /** Смена идентификатора сессии при входе (SEC-06): прежняя сессия этого браузера завершается. */
    private void endCurrentSession(HttpServletRequest request) {
        var actor = CurrentActor.get();
        var session = SessionInfo.current();
        if (actor.isPresent() && session.isPresent()) {
            Tenancy.run(actor.get().tenantId(), () -> login.logout(session.get().sessionId()));
        }
        pending(request).ifPresent(p -> Tenancy.run(p.tenantId(), () -> login.logout(p.sessionId())));
    }

    private static Optional<SessionAuthenticationFilter.PendingSession> pending(HttpServletRequest request) {
        return Optional.ofNullable((SessionAuthenticationFilter.PendingSession)
                request.getAttribute(SessionAuthenticationFilter.PENDING));
    }

    private static SessionAuthenticationFilter.PendingSession requirePending(HttpServletRequest request) {
        return pending(request).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "login_required",
                "Начните вход заново"));
    }
}
