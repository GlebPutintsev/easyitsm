package ru.easyitsm.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.web.ApiException;

/**
 * Вход через OIDC: Keycloak или IdP заказчика (SEC-01). SAML, Kerberos и LDAP-федерация подключаются в Keycloak
 * как брокер, приложение видит только OIDC.
 * <p>
 * Сессии HTTP нет, поэтому параметры запроса авторизации (state, nonce, PKCE) хранятся в зашифрованной куке
 * на 10 минут. После ответа IdP пользователь создаётся или обновляется по {@code iss + sub} и получает
 * обычную сессию в куке.
 */
@Component
class OidcLogin implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLogin.class);
    private static final String COOKIE = "eitsm_oidc";
    private static final String TENANT_ATTR = OidcLogin.class.getName() + ".tenant";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final Secrets secrets;
    private final Tenancy tenancy;
    private final UserAdminService userAdmin;
    private final LoginService login;
    private final SessionCookies cookies;
    private final TransactionTemplate tx;
    private final boolean secure;

    OidcLogin(Secrets secrets, Tenancy tenancy, UserAdminService userAdmin, LoginService login, SessionCookies cookies,
              TransactionTemplate tx, IdentityProperties props) {
        this.secrets = secrets;
        this.tenancy = tenancy;
        this.userAdmin = userAdmin;
        this.login = login;
        this.cookies = cookies;
        this.tx = tx;
        this.secure = props.session().secureCookie();
    }

    private record Stored(OAuth2AuthorizationRequest request, UUID tenant) implements Serializable {
    }

    AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests() {
        return new AuthorizationRequestRepository<>() {
            @Override
            public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
                return read(request).map(s -> {
                    request.setAttribute(TENANT_ATTR, s.tenant());
                    return s.request();
                }).orElse(null);
            }

            @Override
            public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                                 HttpServletRequest request, HttpServletResponse response) {
                if (authorizationRequest == null) {
                    writeCookie(response, "", Duration.ZERO);
                    return;
                }
                UUID tenant = tenancy.resolve(request.getParameter("tenant"))
                        .orElseThrow(() -> ApiException.notFound("Организация"));
                writeCookie(response, seal(new Stored(authorizationRequest, tenant)), TTL);
            }

            @Override
            public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                         HttpServletResponse response) {
                OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
                writeCookie(response, "", Duration.ZERO);
                return loaded;
            }
        };
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        SecurityContextHolder.clearContext();
        UUID tenant = (UUID) request.getAttribute(TENANT_ATTR);
        if (tenant == null || !(authentication instanceof OAuth2AuthenticationToken token)) {
            response.sendRedirect("/login?error=sso");
            return;
        }
        Claims claims = Claims.of(token.getPrincipal());
        RequestInfo info = RequestInfo.of(request);
        try {
            LoginService.Issued issued = Tenancy.in(tenant, () -> tx.execute(status -> {
                UserAccount user = userAdmin.provision("oidc", claims.externalId(), claims.login(), claims.email(),
                        claims.name());
                return login.ssoLogin(user, "sso:" + token.getAuthorizedClientRegistrationId(), info);
            }));
            cookies.writeSession(response, issued.session().token(), issued.session().expiresAt());
            response.sendRedirect(LoginService.OK.equals(issued.status()) ? "/" : "/login?step=" + issued.status());
        } catch (ApiException e) {
            log.info("Вход через SSO отклонён: {}", e.code());
            response.sendRedirect("/login?error=" + ("login_taken".equals(e.code()) ? "sso_conflict" : "sso"));
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        log.info("Ошибка входа через SSO: {}", exception.getClass().getSimpleName());
        writeCookie(response, "", Duration.ZERO);
        response.sendRedirect("/login?error=sso");
    }

    /** Данные пользователя из ID-токена или userinfo. */
    record Claims(String externalId, String login, String email, String name) {

        static Claims of(OAuth2User user) {
            Map<String, Object> a = user.getAttributes();
            String sub = String.valueOf(a.getOrDefault("sub", user.getName()));
            String issuer = user instanceof OidcUser oidc && oidc.getIssuer() != null
                    ? oidc.getIssuer().toString() : "";
            String email = a.get("email") instanceof String s && !s.isBlank() ? s : null;
            String login = a.get("preferred_username") instanceof String s && !s.isBlank() ? s
                    : email != null ? email : sub;
            String name = a.get("name") instanceof String s && !s.isBlank() ? s : login;
            return new Claims(issuer + "|" + sub, login, email, name);
        }
    }

    private Optional<Stored> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (var c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && !c.getValue().isBlank()) {
                try {
                    byte[] plain = secrets.decrypt(Base64.getUrlDecoder().decode(c.getValue()), "oidc");
                    try (var in = new ObjectInputStream(new ByteArrayInputStream(plain))) {
                        // Кука зашифрована и подписана нашим ключом, фильтр — дополнительная защита.
                        in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                                "java.base/*;org.springframework.security.**;ru.easyitsm.identity.internal.*;!*"));
                        return Optional.of((Stored) in.readObject());
                    }
                } catch (RuntimeException | IOException | ClassNotFoundException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private String seal(Stored stored) {
        try (var bytes = new ByteArrayOutputStream(); var out = new ObjectOutputStream(bytes)) {
            out.writeObject(stored);
            out.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.encrypt(bytes.toByteArray(), "oidc"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path("/api/v1/auth/oidc").maxAge(maxAge).build().toString());
    }
}
