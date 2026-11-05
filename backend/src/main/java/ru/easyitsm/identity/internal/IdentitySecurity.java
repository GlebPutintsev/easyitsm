package ru.easyitsm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import ru.easyitsm.platform.security.SecurityChainCustomizer;

/**
 * Встраивает identity в цепочку безопасности платформы: вход по куке сессии, CSRF, публичные эндпоинты входа
 * и вход через OIDC (Keycloak или IdP заказчика), если настроены регистрации клиентов
 * {@code spring.security.oauth2.client.registration.*}.
 */
@Configuration(proxyBeanMethods = false)
class IdentitySecurity {

    static final String AUTH_PATH = "/api/v1/auth/";
    static final String OIDC_START = "/api/v1/auth/oidc/start";
    static final String OIDC_CALLBACK = "/api/v1/auth/oidc/callback/*";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityChainCustomizer identitySecurityCustomizer(SessionStore sessions, SessionCookies cookies,
                                                       IdentityProperties props,
                                                       ObjectProvider<ClientRegistrationRepository> clients,
                                                       OidcLogin oidc) {
        return http -> {
            http.addFilterBefore(new SessionAuthenticationFilter(sessions, cookies, props),
                    AnonymousAuthenticationFilter.class);

            // CSRF (SEC-06): кука XSRF-TOKEN читается интерфейсом и возвращается в заголовке X-XSRF-TOKEN.
            // Проверяются изменяющие запросы с кукой сессии и все запросы входа (защита от подмены входа).
            // Запросы без куки сессии (интеграции с токеном, вход для разработки) подделать с чужого сайта нельзя.
            CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            csrfRepository.setCookieCustomizer(c -> c.path("/").secure(props.session().secureCookie())
                    .sameSite("Strict"));
            RequestMatcher csrfRequired = request -> !SAFE_METHODS.contains(request.getMethod())
                    && (cookies.readSession(request).isPresent() || isAuthEndpoint(request));
            http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfRepository)
                    .requireCsrfProtectionMatcher(csrfRequired));

            http.authorizeHttpRequests(auth -> auth.requestMatchers(AUTH_PATH + "**").permitAll());

            ClientRegistrationRepository registrations = clients.getIfAvailable();
            if (registrations != null) {
                http.oauth2Login(o -> o
                        .loginPage("/login")
                        .authorizationEndpoint(a -> a.baseUri(OIDC_START)
                                .authorizationRequestRepository(oidc.authorizationRequests()))
                        .redirectionEndpoint(r -> r.baseUri(OIDC_CALLBACK))
                        .successHandler(oidc)
                        .failureHandler(oidc));
            }
        };
    }

    private static boolean isAuthEndpoint(HttpServletRequest request) {
        return request.getRequestURI().startsWith(AUTH_PATH);
    }
}
