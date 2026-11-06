package ru.easyitsm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** Имена людей для любого вошедшего: GET /api/v1/people. */
class PeopleApiTest extends IdentityTestSupport {

    private MockHttpServletRequestBuilder asRequester(MockHttpServletRequestBuilder req) {
        return req.header("X-Dev-User", Ids.newId().toString()).header("X-Dev-Tenant", DEMO.toString())
                .header("X-Dev-Roles", "REQ");
    }

    @Test
    void requesterResolvesNamesByIdsIncludingBlocked() throws Exception {
        UUID active = userId(newUser());
        UUID blocked = userId(newUser());
        TenantContext.runAs(DEMO, () -> jdbc.sql("update user_account set state = 'blocked' where id = :id")
                .param("id", blocked).update());
        UUID unknown = Ids.newId();

        String json = mvc.perform(asRequester(get("/api/v1/people").param("ids", active + "," + blocked + "," + unknown)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(json, "$[*].id");
        assertThat(ids).containsExactlyInAnyOrder(active.toString(), blocked.toString());
        List<Boolean> blockedFlag = JsonPath.read(json, "$[?(@.id == '" + blocked + "')].active");
        assertThat(blockedFlag).containsExactly(false);
        List<String> name = JsonPath.read(json, "$[?(@.id == '" + active + "')].displayName");
        assertThat(name).singleElement().asString().startsWith("Тест ");
        // Только имя и почта: роли, группы и логин наружу не уходят.
        assertThat(json).doesNotContain("\"login\"").doesNotContain("\"roles\"");
    }

    @Test
    void searchFindsOnlyActive() throws Exception {
        String login = newUser();
        String blockedLogin = newUser();
        TenantContext.runAs(DEMO, () -> jdbc.sql("update user_account set state = 'blocked' where login = :l")
                .param("l", blockedLogin).update());

        mvc.perform(asRequester(get("/api/v1/people").param("q", login.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value(login + "@example.test"))
                .andExpect(jsonPath("$[0].active").value(true));
        mvc.perform(asRequester(get("/api/v1/people").param("q", blockedLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void validatesInput() throws Exception {
        mvc.perform(asRequester(get("/api/v1/people"))).andExpect(status().isBadRequest());
        mvc.perform(asRequester(get("/api/v1/people").param("ids", "not-a-uuid"))).andExpect(status().isBadRequest());
        String tooMany = IntStream.range(0, PeopleController.MAX_IDS + 1).mapToObj(i -> Ids.newId().toString())
                .collect(Collectors.joining(","));
        mvc.perform(asRequester(get("/api/v1/people").param("ids", tooMany))).andExpect(status().isBadRequest());
        mvc.perform(asRequester(get("/api/v1/people").param("q", "a").param("limit", "51")))
                .andExpect(status().isBadRequest());
        mvc.perform(asRequester(get("/api/v1/people").param("ids", ""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/people").param("q", "тест")).andExpect(status().isUnauthorized());
    }
}
