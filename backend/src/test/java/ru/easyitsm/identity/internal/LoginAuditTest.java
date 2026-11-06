package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Вход и отказ во входе попадают в журнал аудита (SEC-29) без пароля (SEC-30). */
class LoginAuditTest extends IdentityTestSupport {

    @Test
    void loginAndFailedLoginAreAudited() throws Exception {
        String login = newUser();
        Browser browser = new Browser(mvc).open();
        browser.post("/api/v1/auth/login", loginJson(login, "Wrong-Password-1")).andExpect(status().is4xxClientError());
        loggedIn(login);

        List<String> rows = Tenancy.in(DEMO, () -> jdbc.sql("""
                        select result || ' ' || coalesce(actor_login, '') || ' ' || details::text
                          from audit_log where action = 'auth.login' and object_id = :u order by seq""")
                .param("u", userId(login).toString()).query(String.class).list());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).startsWith("failure " + login).contains("\"method\": \"password\"");
        assertThat(rows.get(1)).startsWith("success " + login);
        assertThat(String.join(" ", rows)).doesNotContain("Wrong-Password-1").doesNotContain(PASSWORD);
    }
}
