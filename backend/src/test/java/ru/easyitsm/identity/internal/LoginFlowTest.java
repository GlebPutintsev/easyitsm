package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.identity.events.AccountLocked;
import ru.easyitsm.identity.events.PasswordResetRequested;
import ru.easyitsm.identity.events.UserBlocked;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Сквозные сценарии входа через HTTP: куки, CSRF, MFA, блокировки, сессии, повторная аутентификация,
 * разделение обязанностей. Тестовый профиль работает в режиме MULTI: арендатор передаётся кодом «demo».
 */
class LoginFlowTest extends IdentityTestSupport {

    // --- вход по паролю, куки и CSRF ---

    @Test
    void requesterLogsInWithPasswordAndGetsHardenedCookie() throws Exception {
        String login = newUser();
        Browser browser = new Browser(mvc).open();
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank();

        var result = browser.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andReturn().getResponse();
        String setCookie = result.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("__Host-eitsm_session="))
                .findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/").doesNotContain("Domain");

        browser.get("/api/v1/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(login))
                .andExpect(jsonPath("$.roles[0]").value("REQ"))
                .andExpect(jsonPath("$.session.idleTimeoutSeconds").value(1800));
    }

    @Test
    void anonymousGets401() throws Exception {
        new Browser(mvc).get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        String login = newUser();
        new Browser(mvc).open().withoutCsrf().post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void cookieSessionRequiresCsrfForChanges() throws Exception {
        String login = newUser();
        Browser browser = loggedIn(login);
        browser.withoutCsrf().post("/api/v1/me/sessions/revoke-others", "{}").andExpect(status().isForbidden());
        new Browser(mvc).get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndUnknownUserLookTheSame() throws Exception {
        String login = newUser();
        String wrong = new Browser(mvc).open().post("/api/v1/auth/login", loginJson(login, "Wrong-password-123"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = new Browser(mvc).open().post("/api/v1/auth/login", loginJson("nobody-" + Ids.newId(), "x"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(wrong, "$.code")).isEqualTo("invalid_credentials");
        assertThat(JsonPath.<String>read(unknown, "$.code")).isEqualTo("invalid_credentials");
        assertThat(JsonPath.<String>read(wrong, "$.detail")).isEqualTo(JsonPath.read(unknown, "$.detail"));
    }

    @Test
    void unknownTenantLooksLikeWrongPassword() throws Exception {
        String login = newUser();
        new Browser(mvc).open().post("/api/v1/auth/login",
                        "{\"login\":\"" + login + "\",\"password\":\"" + PASSWORD + "\",\"tenant\":\"no-such\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void accountIsLockedAfterFiveFailures() throws Exception {
        String login = newUser();
        Browser browser = new Browser(mvc).open();
        for (int i = 0; i < 5; i++) {
            browser.post("/api/v1/auth/login", loginJson(login, "Wrong-password-" + i)).andExpect(status().isUnauthorized());
        }
        browser.post("/api/v1/auth/login", loginJson(login, PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(events.stream(AccountLocked.class)).hasSize(1);
    }

    @Test
    void loginRotatesSessionIdentifier() throws Exception {
        String login = newUser();
        Browser browser = loggedIn(login);
        String first = browser.cookie(browser.sessionCookieName());
        browser.post("/api/v1/auth/login", loginJson(login, PASSWORD)).andExpect(status().isOk());
        String second = browser.cookie(browser.sessionCookieName());
        assertThat(second).isNotEqualTo(first);

        Browser stale = new Browser(mvc);
        stale.setCookie(browser.sessionCookieName(), first);
        stale.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsSession() throws Exception {
        Browser browser = loggedIn(newUser());
        String token = browser.cookie(browser.sessionCookieName());
        browser.post("/api/v1/auth/logout", "{}").andExpect(status().isNoContent());
        Browser replay = new Browser(mvc);
        replay.setCookie("__Host-eitsm_session", token);
        replay.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    // --- сроки сессии (SEC-07) и блокировка (ACC-10) ---

    @Test
    void idleSessionExpires() throws Exception {
        String login = newUser();
        Browser browser = loggedIn(login);
        TenantContext.runAs(DEMO, () -> jdbc.sql("""
                update user_session set last_seen_at = now() - interval '31 minutes'
                 where user_id = (select id from user_account where login = :login)""")
                .param("login", login).update());
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void privilegedSessionHasShorterIdleTimeout() throws Exception {
        String login = newUser("ADM");
        Browser browser = loggedInWithTotp(login);
        browser.get("/api/v1/me").andExpect(jsonPath("$.session.idleTimeoutSeconds").value(900));
        TenantContext.runAs(DEMO, () -> jdbc.sql("""
                update user_session set last_seen_at = now() - interval '16 minutes'
                 where user_id = (select id from user_account where login = :login)""")
                .param("login", login).update());
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void blockedUserLosesAccessImmediately() throws Exception {
        String login = newUser();
        Browser user = loggedIn(login);
        Browser admin = loggedInWithTotp(newUser("ADM"));
        UUID id = userId(login);
        admin.post("/api/v1/users/" + id + "/block", "{}").andExpect(status().isOk());
        user.get("/api/v1/me").andExpect(status().isUnauthorized());
        new Browser(mvc).open().post("/api/v1/auth/login", loginJson(login, PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(events.stream(UserBlocked.class)).anyMatch(e -> e.userId().equals(id));
    }

    // --- MFA (SEC-02) ---

    @Test
    void agentMustEnrollMfaBeforeAccess() throws Exception {
        String login = newUser("AGT");
        Browser browser = new Browser(mvc).open();
        browser.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(jsonPath("$.status").value("mfa_setup_required"));
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
        browser.get("/api/v1/auth/config").andExpect(jsonPath("$.pendingStep").value("mfa_setup"));

        String setup = browser.post("/api/v1/auth/mfa/setup/totp", "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        byte[] secret = base32Decode(JsonPath.read(setup, "$.secret"));
        browser.post("/api/v1/auth/mfa/setup/totp/confirm", "{\"code\":\"000000\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_code"));
        String done = browser.post("/api/v1/auth/mfa/setup/totp/confirm",
                        "{\"code\":\"" + Totp.code(secret, Totp.stepAt(Instant.now())) + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andReturn().getResponse().getContentAsString();
        List<String> recovery = JsonPath.read(done, "$.recoveryCodes");
        assertThat(recovery).hasSize(10);
        browser.get("/api/v1/me").andExpect(status().isOk()).andExpect(jsonPath("$.security.totp").value(true));

        // Следующий вход: пароль, затем код.
        Browser next = new Browser(mvc).open();
        next.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(jsonPath("$.status").value("mfa_required"))
                .andExpect(jsonPath("$.methods").value(org.hamcrest.Matchers.hasItems("totp", "recovery")));
        next.post("/api/v1/auth/mfa", "{\"method\":\"totp\",\"code\":\"" + Totp.code(secret,
                Totp.stepAt(Instant.now()) + 1) + "\",\"rememberDevice\":true}").andExpect(status().isOk());
        next.get("/api/v1/me").andExpect(status().isOk());

        // Запомненное устройство пропускает второй фактор.
        String device = next.cookie("__Host-eitsm_device");
        assertThat(device).isNotBlank();
        next.post("/api/v1/auth/login", loginJson(login, PASSWORD)).andExpect(jsonPath("$.status").value("ok"));

        // Резервный код одноразовый.
        Browser third = new Browser(mvc).open();
        third.post("/api/v1/auth/login", loginJson(login, PASSWORD));
        third.post("/api/v1/auth/mfa", "{\"method\":\"recovery\",\"code\":\"" + recovery.getFirst() + "\"}")
                .andExpect(status().isOk());
        Browser fourth = new Browser(mvc).open();
        fourth.post("/api/v1/auth/login", loginJson(login, PASSWORD));
        fourth.post("/api/v1/auth/mfa", "{\"method\":\"recovery\",\"code\":\"" + recovery.getFirst() + "\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void totpCodeCannotBeReplayed() throws Exception {
        String login = newUser("AGT");
        Browser first = loggedInWithTotp(login);
        byte[] secret = totpSecret(login);
        String code = Totp.code(secret, Totp.stepAt(Instant.now()) + 1);
        Browser a = new Browser(mvc).open();
        a.post("/api/v1/auth/login", loginJson(login, PASSWORD));
        a.post("/api/v1/auth/mfa", "{\"method\":\"totp\",\"code\":\"" + code + "\"}").andExpect(status().isOk());
        Browser b = new Browser(mvc).open();
        b.post("/api/v1/auth/login", loginJson(login, PASSWORD));
        b.post("/api/v1/auth/mfa", "{\"method\":\"totp\",\"code\":\"" + code + "\"}").andExpect(status().isUnauthorized());
        first.get("/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void pendingSessionCannotCallApi() throws Exception {
        String login = newUser("AGT");
        loggedInWithTotp(login);
        Browser browser = new Browser(mvc).open();
        browser.post("/api/v1/auth/login", loginJson(login, PASSWORD)).andExpect(jsonPath("$.status").value("mfa_required"));
        browser.get("/api/v1/users").andExpect(status().isUnauthorized());
        browser.get("/api/v1/me").andExpect(status().isUnauthorized());
    }

    @Test
    void cannotRemoveLastFactorWhenMfaRequired() throws Exception {
        Browser browser = loggedInWithTotp(newUser("AGT"));
        browser.delete("/api/v1/me/mfa/totp").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last_factor"));
    }

    // --- повторная аутентификация (SEC-08) и разделение обязанностей (ACC-05) ---

    @Test
    void roleGrantRequiresRecentReauthentication() throws Exception {
        String secLogin = newUser("SEC");
        Browser sec = loggedInWithTotp(secLogin);
        UUID target = userId(newUser());
        String grant = "{\"role\":\"AGT\",\"userId\":\"" + target + "\",\"reason\":\"Новый агент\"}";

        expireReauth(secLogin);
        sec.post("/api/v1/role-grants", grant).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("reauth_required"));
        sec.post("/api/v1/auth/reauth", "{\"password\":\"wrong-password-1\"}").andExpect(status().isUnauthorized());
        sec.post("/api/v1/auth/reauth", "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());
        sec.post("/api/v1/role-grants", grant).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("AGT"));
    }

    @Test
    void securityAdminCannotGrantRolesToSelf() throws Exception {
        String secLogin = newUser("SEC");
        Browser sec = loggedInWithTotp(secLogin);
        sec.post("/api/v1/role-grants", "{\"role\":\"ADM\",\"userId\":\"" + userId(secLogin)
                        + "\",\"reason\":\"Хочу всё\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("self_grant_forbidden"));
    }

    @Test
    void securityAdminCannotGrantRolesToOwnGroup() throws Exception {
        String secLogin = newUser("SEC");
        Browser sec = loggedInWithTotp(secLogin);
        UUID group = Ids.newId();
        TenantContext.runAs(DEMO, () -> {
            jdbc.sql("""
                    insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                    values (:id, :t, :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + group).update();
            jdbc.sql("insert into group_member (tenant_id, group_id, user_id) values (:t, :g, :u)")
                    .param("t", DEMO).param("g", group).param("u", userId(secLogin)).update();
        });
        sec.post("/api/v1/role-grants", "{\"role\":\"ADM\",\"groupId\":\"" + group + "\",\"reason\":\"Обход\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotAddSelfToGroupWithRoles() throws Exception {
        String admLogin = newUser("ADM");
        Browser adm = loggedInWithTotp(admLogin);
        // Группа Service Desk из демо-данных имеет роль AGT.
        adm.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/groups/01930000-0000-7000-8000-0000000000b1/members/" + userId(admLogin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void requesterCannotListUsersOrGrantRoles() throws Exception {
        Browser user = loggedIn(newUser());
        user.get("/api/v1/users").andExpect(status().isForbidden());
        user.post("/api/v1/role-grants", "{\"role\":\"ADM\",\"userId\":\"" + Ids.newId() + "\",\"reason\":\"x\"}")
                .andExpect(status().isForbidden());
        user.post("/api/v1/users", "{\"login\":\"x\",\"displayName\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test
    void userOfAnotherTenantIsNotFound() throws Exception {
        Browser adm = loggedInWithTotp(newUser("ADM"));
        UUID foreign = Ids.newId();
        TenantContext.runAs(UUID.fromString("01930000-0000-7000-8000-000000000002"), () -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (:id, '01930000-0000-7000-8000-000000000002', :login, 'Чужой', now(), now(), 0)""")
                .param("id", foreign).param("login", "foreign-" + foreign).update());
        adm.get("/api/v1/users/" + foreign).andExpect(status().isNotFound());
        adm.post("/api/v1/users/" + foreign + "/block", "{}").andExpect(status().isNotFound());
    }

    // --- сессии (SEC-09) ---

    @Test
    void userSeesAndEndsOwnSessions() throws Exception {
        String login = newUser();
        Browser laptop = loggedIn(login);
        Browser phone = loggedIn(login);
        String sessions = laptop.get("/api/v1/me/sessions").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(sessions, "$")).hasSize(2);
        assertThat(JsonPath.<List<Boolean>>read(sessions, "$[*].current")).containsExactlyInAnyOrder(true, false);

        laptop.post("/api/v1/me/sessions/revoke-others", "{}").andExpect(status().isNoContent());
        phone.get("/api/v1/me").andExpect(status().isUnauthorized());
        laptop.get("/api/v1/me").andExpect(status().isOk());
    }

    @Test
    void cannotEndSessionOfAnotherUser() throws Exception {
        Browser alice = loggedIn(newUser());
        Browser bob = loggedIn(newUser());
        String bobSessions = bob.get("/api/v1/me/sessions").andReturn().getResponse().getContentAsString();
        String bobSession = JsonPath.read(bobSessions, "$[0].id");
        alice.delete("/api/v1/me/sessions/" + bobSession).andExpect(status().isNotFound());
        bob.get("/api/v1/me").andExpect(status().isOk());
    }

    // --- пароль (SEC-04) ---

    @Test
    void passwordPolicyIsEnforced() throws Exception {
        Browser browser = loggedIn(newUser());
        browser.post("/api/v1/me/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"short\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("password_too_short"));
        browser.post("/api/v1/me/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"qwertyuiop123\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("password_breached"));
        browser.post("/api/v1/me/password", "{\"currentPassword\":\"nope-nope-nope\",\"newPassword\":\"Another-Strong-77\"}")
                .andExpect(status().isBadRequest());
        browser.post("/api/v1/me/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Strong-77\"}")
                .andExpect(status().isNoContent());
    }

    @Test
    void passwordIsStoredAsArgon2id() {
        String login = newUser();
        String hash = Tenancy.in(DEMO, () -> jdbc.sql(
                "select password_hash from user_credential where user_id = :u").param("u", userId(login))
                .query(String.class).single());
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    void forgotPasswordAnswersTheSameAndResetWorks() throws Exception {
        String login = newUser();
        new Browser(mvc).open().post("/api/v1/auth/password/forgot", "{\"login\":\"nobody\",\"tenant\":\"demo\"}")
                .andExpect(status().isAccepted());
        Browser browser = new Browser(mvc).open();
        Browser oldSession = loggedIn(login);
        browser.post("/api/v1/auth/password/forgot", "{\"login\":\"" + login + "\",\"tenant\":\"demo\"}")
                .andExpect(status().isAccepted());
        var event = events.stream(PasswordResetRequested.class).filter(e -> e.userId().equals(userId(login)))
                .findFirst().orElseThrow();
        String token = event.resetUrl().substring(event.resetUrl().indexOf("token=") + 6);

        browser.post("/api/v1/auth/password/reset", "{\"token\":\"" + token + "\",\"password\":\"Brand-New-Secret-9\"}")
                .andExpect(status().isNoContent());
        browser.post("/api/v1/auth/password/reset", "{\"token\":\"" + token + "\",\"password\":\"Brand-New-Secret-10\"}")
                .andExpect(status().isBadRequest());
        oldSession.get("/api/v1/me").andExpect(status().isUnauthorized());
        new Browser(mvc).open().post("/api/v1/auth/login", loginJson(login, "Brand-New-Secret-9"))
                .andExpect(status().isOk());
    }

}
