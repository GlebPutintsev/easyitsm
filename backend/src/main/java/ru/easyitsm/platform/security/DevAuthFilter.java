package ru.easyitsm.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Только для разработки и тестов. См. {@link DevAuthProperties}. */
class DevAuthFilter extends OncePerRequestFilter {

    static final String USER_HEADER = "X-Dev-User";
    static final String TENANT_HEADER = "X-Dev-Tenant";
    static final String ROLES_HEADER = "X-Dev-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader(USER_HEADER);
        String tenant = request.getHeader(TENANT_HEADER);
        if (user != null && tenant != null) {
            try {
                var principal = new ActorPrincipal(UUID.fromString(user), UUID.fromString(tenant), "dev-" + user);
                String roles = request.getHeader(ROLES_HEADER);
                var authorities = roles == null ? List.<SimpleGrantedAuthority>of()
                        : java.util.Arrays.stream(roles.split(","))
                                .map(String::trim).filter(r -> !r.isEmpty())
                                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (IllegalArgumentException ignored) {
                // некорректный UUID — запрос останется неаутентифицированным
            }
        }
        chain.doFilter(request, response);
    }
}
