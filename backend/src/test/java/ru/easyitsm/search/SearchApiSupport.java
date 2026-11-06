package ru.easyitsm.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** Заявки, группы и вызовы глобального поиска для тестов. */
final class SearchApiSupport {

    static final String DEMO = KnowledgeApiSupport.DEMO;

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    final KnowledgeApiSupport kb;

    SearchApiSupport(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.kb = new KnowledgeApiSupport(mvc);
    }

    /** Уникальное слово: тесты не видят заявок друг друга по тексту. */
    static String marker() {
        return "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    User user(String roles) {
        User u = User.of(roles);
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                        insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                        values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                        on conflict (id) do nothing""")
                .param("id", u.id()).param("t", DEMO).param("login", "s" + u.id().replace("-", ""))
                .param("name", "Поиск " + u.id().substring(30)).update());
        return u;
    }

    String group(User... members) {
        String group = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            jdbc.sql("""
                            insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                            values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + group).update();
            for (User m : members) {
                jdbc.sql("""
                                insert into group_member (tenant_id, group_id, user_id)
                                values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                        .param("t", DEMO).param("g", group).param("u", m.id()).update();
            }
        });
        return group;
    }

    /** Инцидент от заявителя; группа ставится напрямую, как это сделала бы маршрутизация. */
    String ticket(User requester, String title, String description, String groupId) throws Exception {
        String res = mvc.perform(requester.sign(post("/api/v1/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"incident\",\"title\":%s,\"description\":%s}"
                                .formatted(KnowledgeApiSupport.q(title), KnowledgeApiSupport.q(description))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", groupId).param("id", id).update());
        return id;
    }

    void confidential(String ticketId, String securityGroupId) {
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                        update ticket set confidential = true, security_group_id = cast(:g as uuid)
                         where id = cast(:id as uuid)""")
                .param("g", securityGroupId).param("id", ticketId).update());
    }

    String search(User u, String q, String types) throws Exception {
        var req = get("/api/v1/search").param("q", q).param("limit", "20");
        if (types != null) {
            req = req.param("types", types);
        }
        return mvc.perform(u.sign(req)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** Id найденных записей вида {@code kind}. */
    static List<String> ids(String json, String kind) {
        return JsonPath.read(json, "$.groups[?(@.kind == '" + kind + "')].items[*].id");
    }
}
