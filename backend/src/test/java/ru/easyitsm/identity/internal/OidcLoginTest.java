package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.TestPropertySource;
import ru.easyitsm.platform.id.Ids;

/** Вход через OIDC: перенаправление к IdP с PKCE и создание пользователя по iss + sub. */
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.keycloak.client-id=easyitsm",
        "spring.security.oauth2.client.registration.keycloak.client-secret=secret",
        "spring.security.oauth2.client.registration.keycloak.client-name=Keycloak",
        "spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email",
        "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/api/v1/auth/oidc/callback/{registrationId}",
        "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://sso.example.test/auth",
        "spring.security.oauth2.client.provider.keycloak.token-uri=https://sso.example.test/token",
        "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://sso.example.test/certs",
        "spring.security.oauth2.client.provider.keycloak.user-name-attribute=sub"})
class OidcLoginTest extends IdentityTestSupport {

    @Autowired
    UserAdminService users;

    @Test
    void ssoButtonIsAdvertisedAndRedirectsWithPkce() throws Exception {
        Browser browser = new Browser(mvc).open();
        browser.get("/api/v1/auth/config").andExpect(jsonPath("$.sso[0].id").value("keycloak"));
        var response = browser.get("/api/v1/auth/oidc/start/keycloak?tenant=demo")
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        String location = response.getRedirectedUrl();
        assertThat(location).startsWith("https://sso.example.test/auth?").contains("code_challenge_method=S256",
                "nonce=", "state=", "redirect_uri=http://localhost/api/v1/auth/oidc/callback/keycloak");
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("eitsm_oidc=") && c.contains("HttpOnly"));
    }

    @Test
    void callbackWithoutStateCookieFails() throws Exception {
        new Browser(mvc).get("/api/v1/auth/oidc/callback/keycloak?code=abc&state=forged")
                .andExpect(status().is3xxRedirection())
                .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?error=sso"));
    }

    @Test
    void provisionsUserByIssuerAndSubject() {
        String sub = Ids.newId().toString();
        var token = new OidcIdToken("t", java.time.Instant.now(), java.time.Instant.now().plusSeconds(60),
                Map.of("sub", sub, "iss", "https://sso.example.test", "preferred_username", "sso-" + sub.substring(24),
                        "email", "sso-" + sub.substring(24) + "@example.test", "name", "Пользователь SSO"));
        var claims = OidcLogin.Claims.of(new DefaultOidcUser(java.util.List.of(), token));
        assertThat(claims.externalId()).isEqualTo("https://sso.example.test|" + sub);
        var first = Tenancy.in(DEMO, () -> tx.execute(s -> users.provision("oidc", claims.externalId(), claims.login(),
                claims.email(), claims.name())));
        var again = Tenancy.in(DEMO, () -> tx.execute(s -> users.provision("oidc", claims.externalId(), claims.login(),
                claims.email(), "Новое имя")));
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.displayName()).isEqualTo("Новое имя");
    }

    @Test
    void ssoCannotTakeOverLocalAccount() {
        String local = newUser();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> Tenancy.in(DEMO, () -> tx.execute(s ->
                users.provision("oidc", "https://evil|" + local, local, null, "Захват")))))
                .isInstanceOf(ru.easyitsm.platform.web.ApiException.class);
    }
}
