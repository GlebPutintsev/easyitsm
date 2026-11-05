package ru.easyitsm.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.platform.security.ActorPrincipal;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Вход по куке сессии на каждом запросе (ACC-01). Один запрос к базе находит сессию, пользователя и его роли;
 * заблокированный пользователь или арендатор сессию не получает (ACC-10).
 * <p>
 * Простой считается по {@code last_seen_at}: 30 минут, для ADM и SEC — 15 (SEC-07). Отметка обновляется
 * не чаще раза в минуту. Сессия, ожидающая второй фактор, не даёт доступа к API: она видна только
 * эндпоинтам входа через атрибут {@link #PENDING}.
 */
class SessionAuthenticationFilter extends OncePerRequestFilter {

    static final String PENDING = SessionAuthenticationFilter.class.getName() + ".pending";
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    private final SessionStore sessions;
    private final SessionCookies cookies;
    private final IdentityProperties.Session props;
    private final Set<Role> defaultRoles;

    SessionAuthenticationFilter(SessionStore sessions, SessionCookies cookies, IdentityProperties props) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.props = props.session();
        this.defaultRoles = Directory.defaultRoles(props);
    }

    /** Сессия после проверки пароля, до второго фактора. */
    record PendingSession(UUID sessionId, UUID tenantId, UUID userId, String login, String state) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> token = cookies.readSession(request);
        if (token.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<SessionStore.Resolved> resolved = sessions.resolve(token.get());
            if (resolved.isEmpty()) {
                cookies.clearSession(response);
            } else {
                apply(resolved.get(), request, response);
            }
        }
        chain.doFilter(request, response);
    }

    private void apply(SessionStore.Resolved s, HttpServletRequest request, HttpServletResponse response) {
        Instant now = Instant.now();
        List<GrantedAuthority> authorities = new ArrayList<>();
        boolean privileged = false;
        for (Role role : defaultRoles) {
            authorities.add(new SimpleGrantedAuthority(role.authority()));
        }
        for (String code : s.roles()) {
            Optional<Role> role = Role.parse(code);
            if (role.isPresent()) {
                if (!defaultRoles.contains(role.get())) {
                    authorities.add(new SimpleGrantedAuthority(role.get().authority()));
                }
                privileged |= role.get().privileged();
            }
        }
        Duration idle = privileged ? props.privilegedIdleTimeout() : props.idleTimeout();
        if (s.lastSeenAt().plus(idle).isBefore(now)) {
            TenantContext.runAs(s.tenantId(), () -> sessions.revoke(s.sessionId(), "idle"));
            cookies.clearSession(response);
            return;
        }
        if (!SessionStore.ACTIVE.equals(s.state())) {
            request.setAttribute(PENDING, new PendingSession(s.sessionId(), s.tenantId(), s.userId(), s.login(),
                    s.state()));
            return;
        }
        var principal = new ActorPrincipal(s.userId(), s.tenantId(), s.login());
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        auth.setDetails(new SessionInfo(s.sessionId(), s.authMethod(), s.createdAt(), s.reauthAt()));
        SecurityContextHolder.getContext().setAuthentication(auth);
        if (s.lastSeenAt().plus(TOUCH_EVERY).isBefore(now)) {
            TenantContext.runAs(s.tenantId(), () -> sessions.touch(s.sessionId()));
        }
    }
}
