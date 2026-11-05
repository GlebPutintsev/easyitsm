package ru.easyitsm.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.identity.events.SecurityFactorChanged;
import ru.easyitsm.platform.security.ActorPrincipal;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.databind.JsonNode;

/** Текущий пользователь и «Профиль → Безопасность» (10.3 ТЗ, SEC-09). */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserAdminService userAdmin;
    private final UserRepository users;
    private final Credentials credentials;
    private final MfaService mfa;
    private final WebAuthnService webauthn;
    private final SessionStore sessions;
    private final LoginService login;
    private final LoginHistory history;
    private final ApplicationEventPublisher events;
    private final IdentityProperties props;

    MeController(UserAdminService userAdmin, UserRepository users, Credentials credentials, MfaService mfa,
                 WebAuthnService webauthn, SessionStore sessions, LoginService login, LoginHistory history,
                 ApplicationEventPublisher events, IdentityProperties props) {
        this.userAdmin = userAdmin;
        this.users = users;
        this.credentials = credentials;
        this.mfa = mfa;
        this.webauthn = webauthn;
        this.sessions = sessions;
        this.login = login;
        this.history = history;
        this.events = events;
        this.props = props;
    }

    record Me(UUID id, UUID tenantId, String login, String displayName, String email, String locale, String timezone,
              String source, Set<String> roles, Security security, SessionView session) {
    }

    record Security(boolean passwordSet, boolean totp, int keys, int recoveryCodesLeft, boolean mfaRequired) {
    }

    record SessionView(UUID id, String authMethod, Instant createdAt, long idleTimeoutSeconds) {
    }

    record SessionRow(UUID id, String authMethod, Instant createdAt, Instant lastSeenAt, String ip, String userAgent,
                      boolean current) {
    }

    record ProfileUpdate(@Pattern(regexp = "ru|en") String locale, @Size(max = 64) String timezone) {
    }

    record PasswordChange(@Size(max = 1024) String currentPassword, @NotBlank @Size(max = 1024) String newPassword) {
    }

    record Code(@NotBlank @Size(max = 32) String code) {
    }

    record KeyRegistration(@NotNull UUID ceremonyId, @NotNull JsonNode credential, @Size(max = 100) String name) {
    }

    record Codes(List<String> recoveryCodes) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Me me() {
        ActorPrincipal actor = actor();
        UserAccount user = userAdmin.find(actor.userId());
        Set<String> roles = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5))
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        var cred = credentials.find(user.getId());
        boolean privileged = roles.contains("ADM") || roles.contains("SEC");
        var session = SessionInfo.current().map(s -> new SessionView(s.sessionId(), s.authMethod(), s.createdAt(),
                (privileged ? props.session().privilegedIdleTimeout() : props.session().idleTimeout()).toSeconds()))
                .orElse(null);
        var security = new Security(cred.map(c -> c.passwordHash() != null).orElse(false),
                cred.map(Credentials.Row::totpEnabled).orElse(false), mfa.webauthnCount(user.getId()),
                credentials.recoveryCodesLeft(user.getId()),
                mfa.mfaRequired(roles.stream().map(r -> ru.easyitsm.identity.api.Role.parse(r).orElse(null))
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet())));
        var view = user.toView();
        return new Me(user.getId(), actor.tenantId(), view.login(), view.displayName(), view.email(), view.locale(),
                view.timezone(), view.source(), roles, security, session);
    }

    @PatchMapping
    @Transactional
    public Me update(@Valid @RequestBody ProfileUpdate cmd) {
        UserAccount user = userAdmin.find(actor().userId());
        if (cmd.timezone() != null) {
            try {
                java.time.ZoneId.of(cmd.timezone());
            } catch (java.time.DateTimeException e) {
                throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс");
            }
            user.setTimezone(cmd.timezone());
        }
        if (cmd.locale() != null) {
            user.setLocale(cmd.locale());
        }
        users.save(user);
        return me();
    }

    @GetMapping("/sessions")
    @Transactional(readOnly = true)
    public List<SessionRow> sessions() {
        UUID current = SessionInfo.current().map(SessionInfo::sessionId).orElse(null);
        return sessions.listActive(actor().userId()).stream()
                .map(s -> new SessionRow(s.id(), s.authMethod(), s.createdAt(), s.lastSeenAt(), s.ip(), s.userAgent(),
                        s.id().equals(current)))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    @Transactional
    public ResponseEntity<Void> endSession(@PathVariable UUID id) {
        if (!sessions.revokeOwn(actor().userId(), id, "user")) {
            throw ApiException.notFound("Сессия");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/revoke-others")
    @Transactional
    public ResponseEntity<Void> endOtherSessions() {
        sessions.revokeAll(actor().userId(), SessionInfo.current().map(SessionInfo::sessionId).orElse(null), "user");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/logins")
    @Transactional(readOnly = true)
    public List<LoginHistory.Entry> logins() {
        return history.recent(actor().userId(), 50);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChange cmd) {
        login.changePassword(actor().userId(), SessionInfo.current().map(SessionInfo::sessionId).orElse(null),
                cmd.currentPassword(), cmd.newPassword());
        return ResponseEntity.noContent().build();
    }

    // «Настроить приложение»

    @PostMapping("/mfa/totp")
    @Transactional
    public MfaService.TotpSetup startTotp() {
        ActorPrincipal actor = actor();
        return mfa.startTotp(actor.userId(), actor.login());
    }

    @PostMapping("/mfa/totp/confirm")
    @Transactional
    public Codes confirmTotp(@Valid @RequestBody Code cmd) {
        ActorPrincipal actor = actor();
        List<String> codes = mfa.confirmTotp(actor.userId(), cmd.code());
        events.publishEvent(new SecurityFactorChanged(actor.tenantId(), actor.userId(), "totp_enabled",
                actor.userId()));
        return new Codes(codes.isEmpty() ? null : codes);
    }

    @DeleteMapping("/mfa/totp")
    @RequiresRecentAuth
    @Transactional
    public ResponseEntity<Void> disableTotp() {
        ActorPrincipal actor = actor();
        requireAnotherFactor(actor.userId(), mfa.webauthnCount(actor.userId()) > 0);
        mfa.disableTotp(actor.userId());
        events.publishEvent(new SecurityFactorChanged(actor.tenantId(), actor.userId(), "totp_disabled",
                actor.userId()));
        return ResponseEntity.noContent().build();
    }

    // «Добавить ключ»

    @GetMapping("/mfa/keys")
    @Transactional(readOnly = true)
    public List<WebAuthnService.KeyView> keys() {
        return webauthn.list(actor().userId());
    }

    @PostMapping("/mfa/keys/options")
    @Transactional
    public WebAuthnService.Options keyOptions() {
        UserAccount user = userAdmin.find(actor().userId());
        return webauthn.registrationOptions(user.getId(), user.login(), user.displayName());
    }

    @PostMapping("/mfa/keys")
    @Transactional
    public Codes addKey(@Valid @RequestBody KeyRegistration cmd) {
        ActorPrincipal actor = actor();
        webauthn.register(actor.userId(), cmd.ceremonyId(), cmd.credential().toString(), cmd.name());
        List<String> codes = credentials.recoveryCodesLeft(actor.userId()) == 0
                ? credentials.replaceRecoveryCodes(actor.userId()) : List.of();
        events.publishEvent(new SecurityFactorChanged(actor.tenantId(), actor.userId(), "webauthn_added",
                actor.userId()));
        return new Codes(codes.isEmpty() ? null : codes);
    }

    @DeleteMapping("/mfa/keys/{id}")
    @RequiresRecentAuth
    @Transactional
    public ResponseEntity<Void> deleteKey(@PathVariable UUID id) {
        ActorPrincipal actor = actor();
        boolean otherFactor = credentials.require(actor.userId()).totpEnabled() || mfa.webauthnCount(actor.userId()) > 1;
        requireAnotherFactor(actor.userId(), otherFactor);
        if (!webauthn.delete(actor.userId(), id)) {
            throw ApiException.notFound("Ключ");
        }
        mfa.dropRecoveryIfNoFactors(actor.userId());
        events.publishEvent(new SecurityFactorChanged(actor.tenantId(), actor.userId(), "webauthn_removed",
                actor.userId()));
        return ResponseEntity.noContent().build();
    }

    /** «Сгенерировать резервные коды» с повторным входом. Старые коды перестают действовать. */
    @PostMapping("/mfa/recovery-codes")
    @RequiresRecentAuth
    @Transactional
    public Codes recoveryCodes() {
        ActorPrincipal actor = actor();
        List<String> codes = mfa.regenerateRecoveryCodes(actor.userId());
        events.publishEvent(new SecurityFactorChanged(actor.tenantId(), actor.userId(), "recovery_codes_generated",
                actor.userId()));
        return new Codes(codes);
    }

    /** Нельзя убрать последний фактор, если MFA для пользователя обязательна (SEC-02). */
    private void requireAnotherFactor(UUID userId, boolean hasOther) {
        if (hasOther) {
            return;
        }
        Set<ru.easyitsm.identity.api.Role> roles = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_"))
                .map(a -> ru.easyitsm.identity.api.Role.parse(a.substring(5)).orElse(null))
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (mfa.mfaRequired(roles)) {
            throw ApiException.conflict("last_factor", "Для вашей роли MFA обязательна. Сначала добавьте другой способ");
        }
    }

    private static ActorPrincipal actor() {
        return CurrentActor.get().orElseThrow(ApiException::forbidden);
    }
}
