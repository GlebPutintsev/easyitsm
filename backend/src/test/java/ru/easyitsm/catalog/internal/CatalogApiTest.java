package ru.easyitsm.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;

/** Каталог, конструктор форм и согласования через API: права, видимость, проверка формы, маршруты. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";
    static final String ADMIN = Ids.newId().toString();
    static final String ALICE = Ids.newId().toString();

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeTickets fakeTickets() {
            return new FakeTickets();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeTickets tickets;

    // ---------- права и видимость ----------

    @Test
    void requesterCannotEditCatalog() throws Exception {
        send(post("/api/v1/catalog/categories"), ALICE, "REQ", "{\"name\":\"ИТ\"}")
                .andExpect(status().isForbidden());
        send(post("/api/v1/catalog/approval-flows"), ALICE, "REQ", "{\"name\":\"x\",\"steps\":[]}")
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/catalog/approval-flows"), ALICE, "REQ")).andExpect(status().isForbidden());
    }

    @Test
    void hiddenAndInactiveItemsLookAbsent() throws Exception {
        String cat = category("Безопасность");
        String tag = unique();
        String secret = item(cat, "{\"name\":\"Доступ к SIEM " + tag + "\",\"visibleRoles\":[\"SEC\"]}");
        String off = item(cat, "{\"name\":\"Старая услуга " + tag + "\",\"active\":false}");
        String open = item(cat, "{\"name\":\"Пропуск " + tag + "\"}");

        mvc.perform(as(get("/api/v1/catalog/items").param("q", tag), ALICE, "REQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(open));
        mvc.perform(as(get("/api/v1/catalog/items").param("q", tag), ALICE, "REQ,SEC"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(as(get("/api/v1/catalog/items/" + secret), ALICE, "REQ")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/catalog/items/" + off), ALICE, "REQ")).andExpect(status().isNotFound());
        send(post("/api/v1/catalog/items/" + secret + "/order"), ALICE, "REQ", "{}").andExpect(status().isNotFound());
        // Администратор видит всё с all=true.
        mvc.perform(as(get("/api/v1/catalog/items").param("q", tag).param("all", "true"), ADMIN, "ADM"))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void anotherTenantGets404() throws Exception {
        String cat = category("Оборудование");
        String id = item(cat, "{\"name\":\"Ноутбук " + unique() + "\"}");
        mvc.perform(as(get("/api/v1/catalog/items/" + id), ALICE, "REQ", OTHER)).andExpect(status().isNotFound());
    }

    @Test
    void categoryFilterIncludesSubcategories() throws Exception {
        String root = category("Связь");
        String child = send(post("/api/v1/catalog/categories"), ADMIN, "ADM",
                "{\"name\":\"Мобильная связь\",\"parentId\":\"" + root + "\"}").andReturn().getResponse()
                .getContentAsString();
        String childId = JsonPath.read(child, "$.id");
        String tag = unique();
        item(childId, "{\"name\":\"SIM-карта " + tag + "\"}");
        mvc.perform(as(get("/api/v1/catalog/items").param("categoryId", root).param("q", tag), ALICE, "REQ"))
                .andExpect(jsonPath("$.items.length()").value(1));
        // Категорию нельзя вложить в собственную ветку.
        send(put("/api/v1/catalog/categories/" + root).header("If-Match", "\"0\""), ADMIN, "ADM",
                "{\"name\":\"Связь\",\"parentId\":\"" + childId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("category_cycle"));
    }

    @Test
    void itemsPageWithCursor() throws Exception {
        String cat = category("Постраничность");
        String tag = unique();
        for (int i = 0; i < 3; i++) {
            item(cat, "{\"name\":\"Услуга " + i + " " + tag + "\"}");
        }
        String page1 = mvc.perform(as(get("/api/v1/catalog/items").param("q", tag).param("limit", "2"), ALICE, "REQ"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        mvc.perform(as(get("/api/v1/catalog/items").param("q", tag).param("limit", "2").param("cursor", cursor),
                        ALICE, "REQ"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Услуга 2 " + tag))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    void favoritesArePerUser() throws Exception {
        String cat = category("Избранное");
        String tag = unique();
        String id = item(cat, "{\"name\":\"Монитор " + tag + "\"}");
        mvc.perform(as(put("/api/v1/catalog/items/" + id + "/favorite"), ALICE, "REQ"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/catalog/items").param("favorites", "true").param("q", tag), ALICE, "REQ"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].favorite").value(true));
        mvc.perform(as(get("/api/v1/catalog/items").param("favorites", "true").param("q", tag), ADMIN, "REQ"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(as(delete("/api/v1/catalog/items/" + id + "/favorite"), ALICE, "REQ"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/catalog/items/" + id), ALICE, "REQ"))
                .andExpect(jsonPath("$.favorite").value(false));
    }

    // ---------- конструктор форм ----------

    static final String FORM = """
            {"fields":[
              {"key":"model","type":"select","label":"Модель","required":true,
               "options":[{"value":"basic","label":"Базовый"},{"value":"pro","label":"Для разработчика"}]},
              {"key":"reason","type":"textarea","label":"Зачем нужен Pro","min":10,
               "visibleWhen":{"field":"model","op":"eq","value":"pro"},
               "requiredWhen":{"field":"model","op":"eq","value":"pro"}},
              {"key":"monitors","type":"number","label":"Мониторы","min":0,"max":3},
              {"key":"phone","type":"text","label":"Телефон","pattern":"\\\\+?[0-9]{10,12}"}
            ]}""";

    @Test
    void schemaErrorsAreRejected() throws Exception {
        String cat = category("Формы");
        send(post("/api/v1/catalog/items"), ADMIN, "ADM", "{\"categoryId\":\"" + cat + "\",\"name\":\"x\",\"form\":"
                + "{\"fields\":[{\"key\":\"a\",\"type\":\"text\",\"label\":\"A\","
                + "\"visibleWhen\":{\"field\":\"b\",\"op\":\"eq\",\"value\":1}},"
                + "{\"key\":\"b\",\"type\":\"text\",\"label\":\"B\"}]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_form_schema"));
        send(post("/api/v1/catalog/items"), ADMIN, "ADM", "{\"categoryId\":\"" + cat + "\",\"name\":\"x\",\"form\":"
                + "{\"fields\":[{\"key\":\"a\",\"type\":\"select\",\"label\":\"A\",\"options\":[]}]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_form_schema"));
        send(post("/api/v1/catalog/items"), ADMIN, "ADM", "{\"categoryId\":\"" + cat + "\",\"name\":\"x\",\"form\":"
                + "{\"fields\":[{\"key\":\"Bad Key\",\"type\":\"text\",\"label\":\"A\"}]}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void formIsValidatedOnServer() throws Exception {
        String cat = category("Оборудование");
        String id = item(cat, "{\"name\":\"Ноутбук " + unique() + "\",\"form\":" + FORM + "}");

        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ", "{\"values\":{}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("form_invalid"))
                .andExpect(jsonPath("$.errors.model").value("required"))
                .andExpect(jsonPath("$.trace_id").exists());
        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ",
                "{\"values\":{\"model\":\"pro\",\"monitors\":5,\"phone\":\"abc\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("required"))
                .andExpect(jsonPath("$.errors.monitors").value("max"))
                .andExpect(jsonPath("$.errors.phone").value("pattern"));
        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ",
                "{\"values\":{\"model\":\"ultra\"}}")
                .andExpect(jsonPath("$.errors.model").value("option"));

        // Значение скрытого поля отбрасывается, неизвестные ключи игнорируются.
        String result = send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ",
                "{\"values\":{\"model\":\"basic\",\"reason\":\"не нужно\",\"monitors\":\"2\",\"hack\":1}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").exists())
                .andExpect(jsonPath("$.awaitingApproval").value(false))
                .andReturn().getResponse().getContentAsString();
        var ticket = tickets.created.get(UUID.fromString(JsonPath.read(result, "$.ticketId")));
        assertThat(ticket.custom()).containsOnlyKeys("model", "monitors");
        assertThat(ticket.type()).isEqualTo("request");
        assertThat(ticket.catalogItemId()).hasToString(id);
        assertThat(ticket.title()).startsWith("Ноутбук");
    }

    @Test
    void orderOnBehalfOfColleague() throws Exception {
        String cat = category("Доступы");
        String colleague = Ids.newId().toString();
        String allowed = item(cat, "{\"name\":\"VPN " + unique() + "\"}");
        String result = send(post("/api/v1/catalog/items/" + allowed + "/order"), ALICE, "REQ",
                "{\"forUserId\":\"" + colleague + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var ticket = tickets.created.get(UUID.fromString(JsonPath.read(result, "$.ticketId")));
        assertThat(ticket.createdForId()).hasToString(colleague);
        assertThat(ticket.requesterId()).hasToString(ALICE);

        String personal = item(cat, "{\"name\":\"Смена пароля " + unique() + "\",\"allowOnBehalf\":false}");
        send(post("/api/v1/catalog/items/" + personal + "/order"), ALICE, "REQ",
                "{\"forUserId\":\"" + colleague + "\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("on_behalf_not_allowed"));
    }

    @Test
    void incidentItemRequiresDescription() throws Exception {
        String cat = category("Сбои");
        String id = item(cat, "{\"name\":\"Не работает принтер " + unique() + "\",\"kind\":\"incident\"}");
        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("description_required"));
        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ",
                "{\"description\":\"Мнёт бумагу\",\"impact\":\"department\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("INC-")));
    }

    // ---------- согласования ----------

    @Test
    void multiStepRouteWithConditionAndQuorum() throws Exception {
        String a = uuid(), b = uuid(), c = uuid(), d = uuid(), e = uuid();
        String flow = flow("""
                {"name":"Оборудование","steps":[
                  {"name":"Руководитель","mode":"any","approvers":["%s","%s"]},
                  {"name":"Комиссия","mode":"quorum","quorum":2,"approvers":["%s","%s","%s"],
                   "condition":{"field":"model","op":"eq","value":"pro"}}]}""".formatted(a, b, c, d, e));
        String item = item(category("Оборудование"),
                "{\"name\":\"Ноутбук " + unique() + "\",\"approvalFlowId\":\"" + flow + "\",\"form\":" + FORM + "}");

        // Базовая модель: второй шаг не включается.
        String basic = order(item, "{\"values\":{\"model\":\"basic\"}}");
        route(basic, ALICE).andExpect(jsonPath("$.steps.length()").value(1));

        String ticket = order(item, "{\"values\":{\"model\":\"pro\",\"reason\":\"Сборка большого проекта\"}}");
        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("pending_approval");
        route(ticket, ALICE)
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].state").value("pending"))
                .andExpect(jsonPath("$.steps[1].state").value("waiting"));

        // Шаг 1 «любой»: решение A закрывает и строку B.
        decide(pendingFor(a, ticket), a, "approve", null).andExpect(status().isOk());
        assertThat(pendingIds(b)).doesNotContain(ticket);
        mvc.perform(as(get("/api/v1/approvals/count"), c, "REQ")).andExpect(jsonPath("$.pending").value(
                org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // Шаг 2 кворум 2 из 3: один отказ не мешает.
        decide(pendingFor(c, ticket), c, "reject", "Дорого").andExpect(status().isOk());
        decide(pendingFor(d, ticket), d, "approve", null).andExpect(status().isOk());
        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("pending_approval");
        decide(pendingFor(e, ticket), e, "approve", "Ок").andExpect(status().isOk());
        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("approved");
        route(ticket, ALICE).andExpect(jsonPath("$.state").value("approved"));

        mvc.perform(as(get("/api/v1/approvals").param("tab", "decided"), e, "REQ"))
                .andExpect(jsonPath("$.items[0].decision").value("approved"));
    }

    @Test
    void rejectionNeedsReasonAndEndsRoute() throws Exception {
        String a = uuid(), b = uuid();
        String flow = flow("{\"name\":\"Все\",\"steps\":[{\"name\":\"Оба\",\"mode\":\"all\",\"approvers\":[\"" + a
                + "\",\"" + b + "\"]}]}");
        String item = item(category("Доступы"), "{\"name\":\"Админ-права " + unique() + "\",\"approvalFlowId\":\""
                + flow + "\"}");
        String ticket = order(item, "{}");
        String approval = pendingFor(a, ticket);

        decide(approval, a, "reject", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("reason_required"));
        // Чужое согласование не видно, даже согласующему другого шага.
        decide(approval, b, "approve", null).andExpect(status().isNotFound());
        decide(approval, a, "reject", "Нет обоснования").andExpect(status().isOk());
        decide(approval, a, "approve", null).andExpect(status().isConflict());

        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("rejected");
        assertThat(tickets.reasons.get(UUID.fromString(ticket))).isEqualTo("Нет обоснования");
        assertThat(pendingIds(b)).doesNotContain(ticket);
    }

    @Test
    void requestInfoKeepsRouteOpen() throws Exception {
        String a = uuid();
        String flow = flow("{\"name\":\"Один\",\"steps\":[{\"name\":\"Шаг\",\"mode\":\"any\",\"approvers\":[\"" + a
                + "\"]}]}");
        String item = item(category("Доступы"), "{\"name\":\"Папка " + unique() + "\",\"approvalFlowId\":\""
                + flow + "\"}");
        String ticket = order(item, "{}");
        String approval = pendingFor(a, ticket);
        decide(approval, a, "request_info", "Какая папка?").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("pending"))
                .andExpect(jsonPath("$.infoRequestedAt").exists());
        decide(approval, a, "approve", null).andExpect(status().isOk());
        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("approved");
    }

    @Test
    void requesterNeverApprovesOwnRequest() throws Exception {
        String other = uuid();
        String flow = flow("{\"name\":\"Сам\",\"steps\":[{\"name\":\"Руководитель\",\"mode\":\"all\",\"approvers\":[\""
                + ALICE + "\",\"" + other + "\"]}]}");
        String item = item(category("Доступы"), "{\"name\":\"Сервер " + unique() + "\",\"approvalFlowId\":\""
                + flow + "\"}");
        String ticket = order(item, "{}");
        assertThat(pendingIds(ALICE)).doesNotContain(ticket);
        assertThat(pendingIds(other)).contains(ticket);

        String onlySelf = flow("{\"name\":\"Только сам\",\"steps\":[{\"name\":\"Шаг\",\"mode\":\"any\",\"approvers\":[\""
                + ALICE + "\"]}]}");
        String item2 = item(category("Доступы"), "{\"name\":\"Сервер2 " + unique() + "\",\"approvalFlowId\":\""
                + onlySelf + "\"}");
        send(post("/api/v1/catalog/items/" + item2 + "/order"), ALICE, "REQ", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_approver"));
    }

    @Test
    void delegationAndRouteVisibility() throws Exception {
        String a = uuid(), deputy = uuid(), stranger = uuid();
        String flow = flow("{\"name\":\"Передача\",\"steps\":[{\"name\":\"Шаг\",\"mode\":\"all\",\"allowDelegation\":true,"
                + "\"approvers\":[\"" + a + "\"]}]}");
        String item = item(category("Доступы"), "{\"name\":\"CRM " + unique() + "\",\"approvalFlowId\":\"" + flow + "\"}");
        String ticket = order(item, "{}");

        send(post("/api/v1/approvals/" + pendingFor(a, ticket) + "/delegate"), a, "REQ",
                "{\"toUserId\":\"" + ALICE + "\"}")
                .andExpect(status().isBadRequest());
        send(post("/api/v1/approvals/" + pendingFor(a, ticket) + "/delegate"), a, "REQ",
                "{\"toUserId\":\"" + deputy + "\",\"comment\":\"В отпуске\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("delegated"));
        assertThat(pendingIds(a)).doesNotContain(ticket);

        route(ticket, stranger).andExpect(status().isNotFound());
        route(ticket, deputy).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/approvals/routes/" + ticket), stranger, "AGT")).andExpect(status().isOk());

        decide(pendingFor(deputy, ticket), deputy, "approve", null).andExpect(status().isOk());
        assertThat(tickets.status.get(UUID.fromString(ticket))).isEqualTo("approved");
    }

    @Test
    void flowValidation() throws Exception {
        send(post("/api/v1/catalog/approval-flows"), ADMIN, "ADM",
                "{\"name\":\"Кворум\",\"steps\":[{\"name\":\"Шаг\",\"mode\":\"quorum\",\"quorum\":3,\"approvers\":[\""
                        + uuid() + "\"]}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_quorum"));
        send(post("/api/v1/catalog/approval-flows"), ADMIN, "ADM",
                "{\"name\":\"Пусто\",\"steps\":[{\"name\":\"Шаг\",\"mode\":\"all\",\"approvers\":[]}]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void editingRequiresIfMatch() throws Exception {
        String cat = category("Версии");
        String id = item(cat, "{\"name\":\"Услуга " + unique() + "\"}");
        String body = "{\"categoryId\":\"" + cat + "\",\"name\":\"Новое имя\"}";
        send(put("/api/v1/catalog/items/" + id), ADMIN, "ADM", body).andExpect(status().isPreconditionRequired());
        send(put("/api/v1/catalog/items/" + id).header("If-Match", "\"0\""), ADMIN, "ADM", body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // Заказы не меняют версию: редактор не получает ложный конфликт.
        send(post("/api/v1/catalog/items/" + id + "/order"), ALICE, "REQ", "{}").andExpect(status().isCreated());
        send(put("/api/v1/catalog/items/" + id).header("If-Match", "\"1\""), ADMIN, "ADM", body)
                .andExpect(status().isOk());
    }

    // ---------- помощники ----------

    private String category(String name) throws Exception {
        String json = send(post("/api/v1/catalog/categories"), ADMIN, "ADM", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String item(String categoryId, String fields) throws Exception {
        String body = "{\"categoryId\":\"" + categoryId + "\"," + fields.substring(1);
        String json = send(post("/api/v1/catalog/items"), ADMIN, "ADM", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String flow(String body) throws Exception {
        String json = send(post("/api/v1/catalog/approval-flows"), ADMIN, "ADM", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String order(String item, String body) throws Exception {
        String json = send(post("/api/v1/catalog/items/" + item + "/order"), ALICE, "REQ", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.ticketId");
    }

    private ResultActions route(String ticket, String user) throws Exception {
        return mvc.perform(as(get("/api/v1/approvals/routes/" + ticket), user, "REQ"));
    }

    private ResultActions decide(String approval, String user, String decision, String comment) throws Exception {
        String body = "{\"decision\":\"" + decision + "\"" + (comment == null ? "" : ",\"comment\":\"" + comment + "\"")
                + "}";
        return send(post("/api/v1/approvals/" + approval + "/decision"), user, "REQ", body);
    }

    private String pendingFor(String approver, String ticket) throws Exception {
        String json = mvc.perform(as(get("/api/v1/approvals"), approver, "REQ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.items[?(@.ticketId == '" + ticket + "')].id");
        assertThat(ids).as("согласование для " + approver).hasSize(1);
        return ids.get(0);
    }

    private List<String> pendingIds(String approver) throws Exception {
        String json = mvc.perform(as(get("/api/v1/approvals"), approver, "REQ"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.items[*].ticketId");
    }

    private ResultActions send(MockHttpServletRequestBuilder req, String user, String roles, String body)
            throws Exception {
        return mvc.perform(as(req, user, roles).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return as(req, user, roles, DEMO);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles,
                                                    String tenant) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
    }

    private static String uuid() {
        return Ids.newId().toString();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
