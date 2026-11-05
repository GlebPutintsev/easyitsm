package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

/** Общая подготовка тестов identity: пользователи, вход, TOTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
abstract class IdentityTestSupport {

    static final UUID DEMO = UUID.fromString("01930000-0000-7000-8000-000000000001");
    static final String PASSWORD = "Correct-Horse-Battery-42";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserAdminService userAdmin;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    Secrets secrets;
    @Autowired
    ApplicationEvents events;


    String newUser(String... roles) {
        String login = "u" + Ids.newId().toString().replace("-", "").substring(20);
        TenantContext.runAs(DEMO, () -> tx.executeWithoutResult(s -> {
            var user = userAdmin.create(new UserCommands.Create(login, login + "@example.test", "Тест " + login, "ru",
                    null, PASSWORD, null, null, null));
            for (String role : roles) {
                jdbc.sql("insert into role_grant (id, tenant_id, role, user_id) values (:id, :t, :r, :u)")
                        .param("id", Ids.newId()).param("t", DEMO).param("r", role).param("u", user.id()).update();
            }
        }));
        return login;
    }

    UUID userId(String login) {
        return Tenancy.in(DEMO, () -> jdbc.sql("select id from user_account where login = :l")
                .param("l", login).query(UUID.class).single());
    }

    byte[] totpSecret(String login) {
        UUID id = userId(login);
        byte[] sealed = Tenancy.in(DEMO, () -> jdbc.sql(
                "select totp_secret from user_credential where user_id = :u").param("u", id).query(byte[].class).single());
        return secrets.decrypt(sealed, Credentials.TOTP_CONTEXT + id);
    }

    void expireReauth(String login) {
        TenantContext.runAs(DEMO, () -> jdbc.sql("""
                update user_session set reauth_at = now() - interval '1 hour'
                 where user_id = (select id from user_account where login = :login)""")
                .param("login", login).update());
    }

    Browser loggedIn(String login) throws Exception {
        Browser browser = new Browser(mvc).open();
        browser.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        return browser;
    }

    /** Вход пользователя с обязательной MFA: при первом входе подключает TOTP. */
    Browser loggedInWithTotp(String login) throws Exception {
        Browser browser = new Browser(mvc).open();
        String status = JsonPath.read(browser.post("/api/v1/auth/login", loginJson(login, PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.status");
        if ("mfa_setup_required".equals(status)) {
            String setup = browser.post("/api/v1/auth/mfa/setup/totp", "{}").andReturn().getResponse()
                    .getContentAsString();
            byte[] secret = base32Decode(JsonPath.read(setup, "$.secret"));
            browser.post("/api/v1/auth/mfa/setup/totp/confirm",
                    "{\"code\":\"" + Totp.code(secret, Totp.stepAt(Instant.now())) + "\"}").andExpect(status().isOk());
        } else {
            assertThat(status).isEqualTo("mfa_required");
            byte[] secret = totpSecret(login);
            browser.post("/api/v1/auth/mfa", "{\"method\":\"totp\",\"code\":\""
                    + Totp.code(secret, Totp.stepAt(Instant.now()) + 1) + "\"}").andExpect(status().isOk());
        }
        return browser;
    }

    static String loginJson(String login, String password) {
        return "{\"login\":\"" + login + "\",\"password\":\"" + password + "\",\"tenant\":\"" + Browser.DEMO_TENANT + "\"}";
    }

    static byte[] base32Decode(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : value.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
