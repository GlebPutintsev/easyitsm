package ru.easyitsm.problems;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Помощники API-тестов проблем и крупных инцидентов: пользователи и группа поддержки в справочнике identity,
 * инциденты в её очереди. Агент {@code agent} (AGT), руководитель {@code lead} (AGT, LEAD) и менеджер проблем
 * {@code prb} (PRB) состоят в группе {@link #group}; {@code outsider} (AGT) — в другой.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ProblemApiSupport {

    public static final String DEMO = "01930000-0000-7000-8000-000000000001";
    public static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    protected final String requester = Ids.newId().toString();
    protected final String agent = Ids.newId().toString();
    protected final String lead = Ids.newId().toString();
    protected final String prb = Ids.newId().toString();
    protected final String outsider = Ids.newId().toString();
    protected String group;

    @BeforeEach
    protected void setUpGroups() {
        group = newGroup(agent, lead, prb);
        newGroup(outsider);
    }

    protected void user(String id) {
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", DEMO).param("login", "p" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    protected String newGroup(String... members) {
        String g = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", g).param("t", DEMO).param("name", "Группа " + g).update());
        for (String m : members) {
            user(m);
            TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                    insert into group_member (tenant_id, group_id, user_id)
                    values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                    .param("t", DEMO).param("g", g).param("u", m).update());
        }
        return g;
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return as(req, user, roles, DEMO);
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles,
                                               String tenant) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    protected ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    protected static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /** Инцидент заявителя в очереди {@link #group}. @return id */
    protected String incident(String title) throws Exception {
        String json = body(perform(as(post("/api/v1/tickets"), requester, "REQ")
                .content("{\"type\":\"incident\",\"title\":\"" + title + "\",\"description\":\"Описание\"}")));
        String id = read(json, "$.id");
        setGroup(id, group);
        return id;
    }

    protected void setGroup(String ticketId, String groupId) {
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", groupId).param("id", ticketId).update());
    }

    protected String ticketEtag(String id, String user, String roles) throws Exception {
        return perform(as(get("/api/v1/tickets/" + id), user, roles)).andReturn().getResponse().getHeader("ETag");
    }

    protected String ticket(String id, String user, String roles) throws Exception {
        return body(perform(as(get("/api/v1/tickets/" + id), user, roles)));
    }

    /** Агент берёт заявку в работу (исполнитель — он). */
    protected void take(String id, String user, String roles) throws Exception {
        perform(as(post("/api/v1/tickets/" + id + "/take"), user, roles)
                .header("If-Match", ticketEtag(id, user, roles)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    protected ResultActions transition(String id, String user, String roles, String json) throws Exception {
        return perform(as(post("/api/v1/tickets/" + id + "/transitions"), user, roles)
                .header("If-Match", ticketEtag(id, user, roles)).content(json));
    }

    protected static String uniq(String s) {
        return s + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
