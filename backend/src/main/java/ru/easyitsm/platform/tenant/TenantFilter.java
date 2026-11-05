package ru.easyitsm.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ставит арендатора запроса в {@link TenantContext}. Встраивается в цепочку Spring Security
 * после аутентификации, см. {@code SecurityConfig}.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final List<TenantResolver> resolvers;

    public TenantFilter(List<TenantResolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> tenant = resolvers.stream()
                .map(r -> r.resolve(request))
                .flatMap(Optional::stream)
                .findFirst();
        try {
            tenant.ifPresent(TenantContext::set);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
