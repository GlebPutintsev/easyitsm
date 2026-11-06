package ru.easyitsm.tickets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Общие помощники API-тестов заявок. Пользователи новые в каждом экземпляре теста; настройки арендатора
 * (правила, поля) тесты создают с уникальными данными, чтобы не влиять друг на друга.
 * <p>
 * Агенты видят только заявки своих групп, поэтому {@code agent} и {@code agent2} заведены в справочнике
 * identity и состоят в группе {@link #supportGroup}, а заявки из {@link #createIncident} попадают в её очередь.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class TicketApiSupport {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    final String requester = Ids.newId().toString();
    final String agent = Ids.newId().toString();
    final String agent2 = Ids.newId().toString();
    final String admin = Ids.newId().toString();

    String tenant = DEMO;

    /** Группа поддержки теста: в ней {@code agent} и {@code agent2}. */
    String supportGroup;

    @BeforeEach
    void setUpSupportGroup() {
        user(agent);
        user(agent2);
        supportGroup = newGroup(agent, agent2);
    }

    /** Пользователь в справочнике identity текущего арендатора (для членства в группах). */
    void user(String id) {
        TenantContext.runAs(UUID.fromString(tenant), () -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", tenant).param("login", "t" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    /** Новая группа поддержки с участниками (заводит их в справочнике). */
    String newGroup(String... members) {
        String group = Ids.newId().toString();
        UUID t = UUID.fromString(tenant);
        TenantContext.runAs(t, () -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", group).param("t", tenant).param("name", "Группа " + group).update());
        for (String m : members) {
            user(m);
            TenantContext.runAs(t, () -> jdbc.sql("""
                    insert into group_member (tenant_id, group_id, user_id)
                    values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                    .param("t", tenant).param("g", group).param("u", m).update());
        }
        return group;
    }

    /**
     * Ставит заявку без группы в очередь {@link #supportGroup}, как это сделала бы группа по умолчанию.
     * Статус не меняется: так тесты жизненного цикла начинают с «Новой».
     */
    void queue(String ticketId) {
        TenantContext.runAs(UUID.fromString(tenant), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid) and group_id is null")
                .param("g", supportGroup).param("id", ticketId).update());
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asReq(MockHttpServletRequestBuilder req) {
        return as(req, requester, "REQ");
    }

    MockHttpServletRequestBuilder asAgent(MockHttpServletRequestBuilder req) {
        return as(req, agent, "AGT");
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, admin, "ADM");
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /**
     * Создаёт инцидент от имени заявителя и возвращает JSON ответа (как его вернул сервер). Если маршрутизация
     * не нашла группу, заявка ставится в очередь {@link #supportGroup}.
     */
    String createIncident(String title) throws Exception {
        String json = body(perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"" + title + "\",\"description\":\"Описание\"}")));
        queue(read(json, "$.id"));
        return json;
    }

    String fetch(String id, String user, String roles) throws Exception {
        return body(perform(as(MockMvcHelpers.get("/api/v1/tickets/" + id), user, roles)));
    }

    String etag(String id) throws Exception {
        return perform(asAgent(MockMvcHelpers.get("/api/v1/tickets/" + id))).andReturn().getResponse().getHeader("ETag");
    }

    ResultActions patchAs(String id, String user, String roles, String json) throws Exception {
        return perform(as(patch("/api/v1/tickets/" + id), user, roles).header("If-Match", etagAs(id, user, roles))
                .content(json));
    }

    String etagAs(String id, String user, String roles) throws Exception {
        return perform(as(MockMvcHelpers.get("/api/v1/tickets/" + id), user, roles)).andReturn().getResponse()
                .getHeader("ETag");
    }

    ResultActions transition(String id, String user, String roles, String json) throws Exception {
        return perform(as(post("/api/v1/tickets/" + id + "/transitions"), user, roles)
                .header("If-Match", etagAs(id, user, roles)).content(json));
    }

    /** Псевдоним, чтобы не путать с методом get(String, …) выше. */
    static final class MockMvcHelpers {
        static MockHttpServletRequestBuilder get(String url) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        }
    }
}
