package ru.easyitsm.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** Справочник рабочего места агента: имена из identity, услуги из каталога, категории и загрузка из заявок. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentDirectoryTest {

    static final UUID DEMO = UUID.fromString("01930000-0000-7000-8000-000000000001");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate tx;

    final String tag = Ids.newId().toString().substring(24);
    final UUID agent = Ids.newId();
    final UUID agent2 = Ids.newId();
    final UUID requester = Ids.newId();
    final UUID support = Ids.newId();
    final UUID security = Ids.newId();
    final UUID item = Ids.newId();
    final UUID hiddenItem = Ids.newId();
    final UUID category = Ids.newId();

    @BeforeEach
    void seed() {
        TenantContext.runAs(DEMO, () -> tx.executeWithoutResult(s -> {
            user(agent, "Агент Первый " + tag);
            user(agent2, "Агент Второй " + tag);
            user(requester, "Заявитель " + tag);
            group(support, "Поддержка " + tag, "support");
            group(security, "Безопасность " + tag, "security");
            member(support, agent);
            member(support, agent2);
            member(security, agent2);
            UUID catalogCategory = Ids.newId();
            jdbc.sql("""
                    insert into catalog_category (id, tenant_id, name, created_at, updated_at, version)
                    values (:id, :t, :n, now(), now(), 0)""")
                    .param("id", catalogCategory).param("t", DEMO).param("n", "Категория " + tag).update();
            catalogItem(item, catalogCategory, "Услуга " + tag, true);
            catalogItem(hiddenItem, catalogCategory, "Выключенная " + tag, false);
            jdbc.sql("""
                    insert into ticket_category (id, tenant_id, name, created_at, updated_at, version)
                    values (:id, :t, :n, now(), now(), 0)""")
                    .param("id", category).param("t", DEMO).param("n", "Не работает " + tag).update();
        }));
    }

    @Test
    void directoryResolvesNamesAndLoad() throws Exception {
        String ticket = body(mvc.perform(as(post("/api/v1/tickets"), requester, "REQ")
                .content("{\"type\":\"incident\",\"title\":\"Не печатает\",\"description\":\"Описание\"}")));
        String id = JsonPath.read(ticket, "$.id");
        // Заявка без группы видна руководителю группы, он её и распределяет (видимость по группам, PR #24).
        String etag = mvc.perform(as(get("/api/v1/tickets/" + id), agent, "AGT,LEAD")).andReturn().getResponse()
                .getHeader("ETag");
        mvc.perform(as(patch("/api/v1/tickets/" + id), agent, "AGT,LEAD").header("If-Match", etag)
                        .content("{\"groupId\":\"" + support + "\",\"assigneeId\":\"" + agent + "\"}"))
                .andExpect(status().isOk());

        String json = body(mvc.perform(as(get("/api/v1/agent-workspace/directory"), agent, "AGT"))
                .andExpect(status().isOk()));

        assertThat((String) JsonPath.read(json, "$.me.id")).isEqualTo(agent.toString());
        assertThat((String) JsonPath.read(json, "$.me.name")).isEqualTo("Агент Первый " + tag);

        List<String> groupIds = JsonPath.read(json, "$.groups[*].id");
        assertThat(groupIds).contains(support.toString()).doesNotContain(security.toString());

        List<Map<String, Object>> first = JsonPath.read(json, "$.agents[?(@.id == '" + agent + "')]");
        assertThat(first).singleElement().satisfies(a -> {
            assertThat(a.get("name")).isEqualTo("Агент Первый " + tag);
            assertThat(a.get("groupIds")).asList().containsExactly(support.toString());
            assertThat(((Number) a.get("openTickets")).longValue()).isEqualTo(1);
            assertThat(a.get("available")).isEqualTo(true);
        });
        // Группа безопасности — не группа назначения, в списке групп агента её нет.
        List<Map<String, Object>> second = JsonPath.read(json, "$.agents[?(@.id == '" + agent2 + "')]");
        assertThat(second).singleElement().satisfies(a -> {
            assertThat(a.get("groupIds")).asList().containsExactly(support.toString());
            assertThat(((Number) a.get("openTickets")).longValue()).isZero();
        });
        // Заявитель не агент.
        List<String> agentIds = JsonPath.read(json, "$.agents[*].id");
        assertThat(agentIds).doesNotContain(requester.toString());

        List<String> services = JsonPath.read(json, "$.services[*].id");
        assertThat(services).contains(item.toString()).doesNotContain(hiddenItem.toString());
        List<String> categories = JsonPath.read(json, "$.categories[*].id");
        assertThat(categories).contains(category.toString());
        List<String> resolution = JsonPath.read(json, "$.resolutionCodes[*].id");
        assertThat(resolution).contains("fixed");
        List<String> pending = JsonPath.read(json, "$.pendingReasons[*].id");
        assertThat(pending).contains("requester");
        List<String> cancel = JsonPath.read(json, "$.cancelReasons[*].id");
        assertThat(cancel).contains("duplicate");
    }

    @Test
    void requesterHasNoAccess() throws Exception {
        mvc.perform(as(get("/api/v1/agent-workspace/directory"), requester, "REQ")).andExpect(status().isForbidden());
    }

    private void user(UUID id, String name) {
        jdbc.sql("""
                insert into user_account (id, tenant_id, login, email, display_name, created_at, updated_at, version)
                values (:id, :t, :login, :email, :name, now(), now(), 0)""")
                .param("id", id).param("t", DEMO).param("login", "ws" + id.toString().substring(24))
                .param("email", "ws" + id.toString().substring(24) + "@example.test").param("name", name).update();
    }

    private void group(UUID id, String name, String type) {
        jdbc.sql("""
                insert into user_group (id, tenant_id, name, type, created_at, updated_at, version)
                values (:id, :t, :n, :type, now(), now(), 0)""")
                .param("id", id).param("t", DEMO).param("n", name).param("type", type).update();
    }

    private void member(UUID group, UUID user) {
        jdbc.sql("insert into group_member (tenant_id, group_id, user_id) values (:t, :g, :u)")
                .param("t", DEMO).param("g", group).param("u", user).update();
    }

    private void catalogItem(UUID id, UUID categoryId, String name, boolean active) {
        jdbc.sql("""
                insert into catalog_item (id, tenant_id, category_id, name, active, created_at, updated_at, version)
                values (:id, :t, :c, :n, :a, now(), now(), 0)""")
                .param("id", id).param("t", DEMO).param("c", categoryId).param("n", name).param("a", active).update();
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, UUID user, String roles) {
        return req.header("X-Dev-User", user.toString()).header("X-Dev-Tenant", DEMO.toString())
                .header("X-Dev-Roles", roles).contentType(MediaType.APPLICATION_JSON);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
