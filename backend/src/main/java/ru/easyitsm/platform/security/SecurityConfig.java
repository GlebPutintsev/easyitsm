package ru.easyitsm.platform.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import ru.easyitsm.platform.tenant.TenantFilter;
import ru.easyitsm.platform.tenant.TenantResolver;

/**
 * Базовая цепочка безопасности. Запрет по умолчанию: всё под {@code /api/**} требует входа.
 * <p>
 * Вход, сессии в куках и CSRF добавляет модуль identity через {@link SecurityChainCustomizer} (SEC-01, SEC-06).
 * Без него CSRF Spring Security включён по умолчанию: изменяющие запросы без токена отклоняются.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, DevAuthProperties devAuth, Environment env,
                                    List<TenantResolver> tenantResolvers,
                                    List<SecurityChainCustomizer> customizers) throws Exception {
        if (devAuth.enabled()) {
            List<String> profiles = Arrays.asList(env.getActiveProfiles());
            if (!profiles.contains("dev") && !profiles.contains("test")) {
                throw new IllegalStateException("easyitsm.security.dev-auth.enabled разрешён только в профилях dev и test");
            }
            http.addFilterBefore(new DevAuthFilter(), AnonymousAuthenticationFilter.class);
        }
        http.addFilterAfter(new TenantFilter(tenantResolvers), AnonymousAuthenticationFilter.class);
        for (SecurityChainCustomizer customizer : customizers) {
            customizer.customize(http);
        }

        return http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
