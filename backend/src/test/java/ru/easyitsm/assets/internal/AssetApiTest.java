package ru.easyitsm.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Активы (AST-01, 03, 04, 05): права, жизненный цикл оборудования, лицензии с учётом количества, договоры
 * с напоминаниями, связь с заявками и изоляция арендаторов.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RenewalJob renewals;

    final String manager = Ids.newId().toString();
    final String employee = Ids.newId().toString();
    final String employee2 = Ids.newId().toString();
    final String agent = Ids.newId().toString();
    final String requester = Ids.newId().toString();
    /** Уникальная часть инвентарных номеров: тесты делят арендатора DEMO. */
    final String run = Ids.newId().toString().substring(24);

    @BeforeEach
    void users() {
        for (String u : List.of(manager, employee, employee2, agent, requester)) {
            user(DEMO, u);
        }
    }

    // ------------------------------------------------------------------ права и изоляция

    @Test
    void anonymousAndRequesterAreRejected() throws Exception {
        mvc.perform(get("/api/v1/assets")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/assets"), requester, "REQ")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/assets"), agent, "AGT").content(asset("A-" + run)))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/licenses"), requester, "REQ")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/contracts"), requester, "REQ")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/vendors"), agent, "AGT").content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void agentReadsAndAnotherTenantGets404() throws Exception {
        String id = create("NB-" + run);
        mvc.perform(as(get("/api/v1/assets/" + id), agent, "AGT")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/assets/" + id), manager, "AST", OTHER))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(as(post("/api/v1/assets/" + id + "/repair"), manager, "AST", OTHER).header("If-Match", "\"0\"")
                .content("{}")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ оборудование (AST-01)

    @Test
    void createValidateAndRejectDuplicateTag() throws Exception {
        String tag = "PC-" + run;
        mvc.perform(as(post("/api/v1/assets"), manager, "AST").content(asset(tag)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.state").value("in_stock"))
                .andExpect(jsonPath("$.endOfLife").value("2029-01-15"));
        mvc.perform(as(post("/api/v1/assets"), manager, "AST").content(asset(tag.toLowerCase())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_tag"));
        mvc.perform(as(post("/api/v1/assets"), manager, "AST").content("{\"tag\":\"X\",\"name\":\"Y\",\"kind\":\"car\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/assets"), manager, "AST").content("{\"name\":\"Без номера\",\"kind\":\"laptop\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("required"));
    }

    @Test
    void lifecycleIssueTransferRepairReturnRetire() throws Exception {
        String id = create("LT-" + run);

        // Выдать — только действующему сотруднику своего арендатора
        mvc.perform(as(post("/api/v1/assets/" + id + "/issue"), manager, "AST").header("If-Match", "\"0\"")
                        .content("{\"holderId\":\"" + Ids.newId() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_user"));
        mvc.perform(as(post("/api/v1/assets/" + id + "/issue"), manager, "AST")
                        .content("{\"holderId\":\"" + employee + "\"}"))
                .andExpect(status().isPreconditionRequired());
        action(id, "issue", "{\"holderId\":\"" + employee + "\",\"comment\":\"Новому сотруднику\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_use"))
                .andExpect(jsonPath("$.holder.id").value(employee))
                .andExpect(jsonPath("$.holder.name").value("Тест " + employee.substring(30)));

        // Сотрудник видит актив в «Моём оборудовании», другой — нет
        mvc.perform(as(get("/api/v1/me/assets"), employee, "REQ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
        mvc.perform(as(get("/api/v1/me/assets"), employee2, "REQ"))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());

        action(id, "issue", "{\"holderId\":\"" + employee2 + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("invalid_state"));
        action(id, "transfer", "{\"holderId\":\"" + employee2 + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.holder.id").value(employee2));
        action(id, "repair", "{\"comment\":\"Не включается\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_repair"))
                .andExpect(jsonPath("$.holder.id").value(employee2));
        action(id, "return", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_stock"))
                .andExpect(jsonPath("$.holder").doesNotExist());

        action(id, "retire", "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        action(id, "retire", "{\"reason\":\"Не подлежит ремонту\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("retired"))
                .andExpect(jsonPath("$.retireReason").value("Не подлежит ремонту"));
        action(id, "issue", "{\"holderId\":\"" + employee + "\"}").andExpect(status().isConflict());

        String history = body(mvc.perform(as(get("/api/v1/assets/" + id + "/history"), manager, "AST")));
        assertThat(JsonPath.<List<String>>read(history, "$[*].kind"))
                .containsExactly("retired", "returned", "repair", "transferred", "issued", "created");
        assertThat(JsonPath.<String>read(history, "$[4].comment")).isEqualTo("Новому сотруднику");

        // Списанные скрыты из списка по умолчанию и видны фильтром
        String list = body(mvc.perform(as(get("/api/v1/assets").param("q", "LT-" + run), manager, "AST")));
        assertThat(JsonPath.<List<?>>read(list, "$.items")).isEmpty();
        list = body(mvc.perform(as(get("/api/v1/assets").param("q", "LT-" + run).param("state", "retired"), manager, "AST")));
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).containsExactly(id);
    }

    @Test
    void patchClearsFieldsAndChecksVersion() throws Exception {
        String id = create("MN-" + run);
        mvc.perform(as(patch("/api/v1/assets/" + id), manager, "AST").header("If-Match", "\"0\"")
                        .content("{\"name\":\"Монитор 27\",\"clear\":[\"serial\",\"cost\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Монитор 27"))
                .andExpect(jsonPath("$.serial").doesNotExist())
                .andExpect(jsonPath("$.cost").doesNotExist())
                .andExpect(jsonPath("$.model").value("ThinkPad T14"));
        mvc.perform(as(patch("/api/v1/assets/" + id), manager, "AST").header("If-Match", "\"0\"")
                        .content("{\"name\":\"Снова\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(patch("/api/v1/assets/" + id), manager, "AST").header("If-Match", "\"1\"")
                        .content("{\"clear\":[\"tag\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filtersAndStats() throws Exception {
        String loc = body(mvc.perform(as(post("/api/v1/asset-locations"), manager, "AST")
                .content("{\"name\":\"Склад " + run + "\",\"address\":\"Москва\"}")).andExpect(status().isCreated()));
        String locId = JsonPath.read(loc, "$.id");
        mvc.perform(as(post("/api/v1/asset-locations"), manager, "AST").content("{\"name\":\"склад " + run + "\"}"))
                .andExpect(status().isConflict());
        String a = create("FL-" + run);
        mvc.perform(as(patch("/api/v1/assets/" + a), manager, "AST").header("If-Match", "\"0\"")
                .content("{\"locationId\":\"" + locId + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.location.name").value("Склад " + run));
        String list = body(mvc.perform(as(get("/api/v1/assets").param("locationId", locId), agent, "AGT")));
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].tag")).containsExactly("FL-" + run);
        mvc.perform(as(get("/api/v1/assets").param("state", "lost"), agent, "AGT")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/assets/stats"), agent, "AGT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inStock").isNumber());
    }

    // ------------------------------------------------------------------ заявки (AST-05)

    @Test
    void linkTicketOnlyWhenVisible() throws Exception {
        String id = create("TK-" + run);
        String group = group(agent);
        String ticket = body(mvc.perform(as(post("/api/v1/tickets"), requester, "REQ")
                .content("{\"type\":\"incident\",\"title\":\"Сломался ноутбук\",\"description\":\"Не включается\"}"))
                .andExpect(status().isCreated()));
        String ticketId = JsonPath.read(ticket, "$.id");
        String number = JsonPath.read(ticket, "$.number");
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", group).param("id", ticketId).update());

        // Менеджер активов без роли поддержки заявку не видит: 404, как в модуле заявок
        mvc.perform(as(post("/api/v1/assets/" + id + "/tickets"), manager, "AST").content("{\"number\":\"" + number + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/assets/" + id + "/tickets"), agent, "AGT").content("{\"number\":\"" + number + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(number));
        String list = body(mvc.perform(as(get("/api/v1/assets").param("ticketId", ticketId), agent, "AGT")));
        assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).containsExactly(id);
        mvc.perform(as(get("/api/v1/assets").param("ticketId", ticketId), manager, "AST"))
                .andExpect(status().isNotFound());

        mvc.perform(as(delete("/api/v1/assets/" + id + "/tickets/" + ticketId), agent, "AGT"))
                .andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/v1/assets/" + id + "/tickets/" + ticketId), agent, "AGT"))
                .andExpect(status().isNotFound());
        String history = body(mvc.perform(as(get("/api/v1/assets/" + id + "/history"), agent, "AGT")));
        assertThat(JsonPath.<List<String>>read(history, "$[*].kind")).contains("ticket_linked", "ticket_unlinked");
        // Тот, кто заявку не видит, не видит в истории и её номер
        String hidden = body(mvc.perform(as(get("/api/v1/assets/" + id + "/history"), manager, "AST")));
        assertThat(JsonPath.<List<Object>>read(hidden, "$[?(@.kind == 'ticket_linked')].ticket")).containsOnlyNulls();
    }

    // ------------------------------------------------------------------ лицензии (AST-03)

    @Test
    void licenseQuantityIsEnforcedAndRetireReleasesSeat() throws Exception {
        String a1 = create("L1-" + run);
        String a2 = create("L2-" + run);
        String lic = body(mvc.perform(as(post("/api/v1/licenses"), manager, "AST")
                        .content("{\"product\":\"Офис " + run + "\",\"metric\":\"device\",\"quantity\":1,"
                                + "\"validTo\":\"2030-12-31\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.available").value(1)));
        String licId = JsonPath.read(lic, "$.id");

        mvc.perform(as(post("/api/v1/licenses/" + licId + "/assignments"), manager, "AST").content("{}"))
                .andExpect(status().isBadRequest());
        String seat = body(mvc.perform(as(post("/api/v1/licenses/" + licId + "/assignments"), manager, "AST")
                .content("{\"assetId\":\"" + a1 + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.asset.id").value(a1)));
        mvc.perform(as(post("/api/v1/licenses/" + licId + "/assignments"), manager, "AST")
                        .content("{\"assetId\":\"" + a2 + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("license_exhausted"));
        mvc.perform(as(get("/api/v1/assets/" + a1 + "/licenses"), agent, "AGT"))
                .andExpect(jsonPath("$[0].licenseId").value(licId));

        // Списание освобождает лицензию
        action(a1, "retire", "{\"reason\":\"Утерян\"}").andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/licenses/" + licId), manager, "AST")).andExpect(jsonPath("$.used").value(0));
        String seat2 = body(mvc.perform(as(post("/api/v1/licenses/" + licId + "/assignments"), manager, "AST")
                .content("{\"assetId\":\"" + a2 + "\"}")).andExpect(status().isCreated()));
        mvc.perform(as(delete("/api/v1/licenses/" + licId + "/assignments/" + JsonPath.read(seat, "$.id")), manager, "AST"))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/v1/licenses/" + licId + "/assignments/" + JsonPath.read(seat2, "$.id")), manager, "AST"))
                .andExpect(status().isNoContent());

        // Лицензия на сотрудника видна ему в «Моём ПО»
        mvc.perform(as(post("/api/v1/licenses/" + licId + "/assignments"), manager, "AST")
                .content("{\"userId\":\"" + employee + "\"}")).andExpect(status().isCreated());
        mvc.perform(as(get("/api/v1/me/licenses"), employee, "REQ"))
                .andExpect(jsonPath("$[?(@.licenseId == '" + licId + "')]").exists());

        String found = body(mvc.perform(as(get("/api/v1/licenses").param("q", "офис " + run), agent, "AGT")));
        assertThat(JsonPath.<List<String>>read(found, "$[*].id")).containsExactly(licId);
    }

    // ------------------------------------------------------------------ договоры (AST-04)

    @Test
    void contractRenewalReminderIsSentOncePerEndDate() throws Exception {
        String vendor = body(mvc.perform(as(post("/api/v1/vendors"), manager, "AST")
                .content("{\"name\":\"Поставщик " + run + "\",\"taxId\":\"7701234567\",\"email\":\"sales@vendor.test\"}"))
                .andExpect(status().isCreated()));
        String vendorId = JsonPath.read(vendor, "$.id");
        mvc.perform(as(post("/api/v1/vendors"), manager, "AST").content("{\"name\":\"V\",\"taxId\":\"12\"}"))
                .andExpect(status().isBadRequest());

        LocalDate today = LocalDate.of(2031, 3, 1);
        String contract = body(mvc.perform(as(post("/api/v1/contracts"), manager, "AST")
                        .content("{\"number\":\"Д-" + run + "\",\"title\":\"Поддержка серверов\",\"kind\":\"support\","
                                + "\"vendorId\":\"" + vendorId + "\",\"startsOn\":\"2030-03-15\",\"endsOn\":\"2031-03-15\","
                                + "\"renewalNoticeDays\":30,\"ownerId\":\"" + manager + "\",\"amount\":120000.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vendor.name").value("Поставщик " + run))
                .andExpect(jsonPath("$.owner.id").value(manager)));
        String id = JsonPath.read(contract, "$.id");
        mvc.perform(as(post("/api/v1/contracts"), manager, "AST")
                        .content("{\"number\":\"д-" + run + "\",\"title\":\"Т\",\"kind\":\"other\",\"startsOn\":\"2030-01-01\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(post("/api/v1/contracts"), manager, "AST")
                        .content("{\"number\":\"Е-" + run + "\",\"title\":\"Т\",\"kind\":\"other\",\"startsOn\":\"2030-01-01\","
                                + "\"endsOn\":\"2029-01-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_period"));

        assertThat(renewals.runOnce(today)).isGreaterThanOrEqualTo(1);
        mvc.perform(as(get("/api/v1/contracts/" + id), manager, "AST"))
                .andExpect(jsonPath("$.remindedFor").value("2031-03-15"));
        renewals.runOnce(today);
        assertThat(notifications(manager)).isEqualTo(1);

        // Продлили — о новой дате окончания напомнят снова
        mvc.perform(as(patch("/api/v1/contracts/" + id), manager, "AST").header("If-Match", "\"1\"")
                .content("{\"endsOn\":\"2031-03-20\"}")).andExpect(status().isOk());
        renewals.runOnce(today);
        assertThat(notifications(manager)).isEqualTo(2);

        mvc.perform(as(get("/api/v1/contracts").param("vendorId", vendorId), agent, "AGT"))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    // ------------------------------------------------------------------ помощники

    private String asset(String tag) {
        return """
                {"tag":"%s","name":"Ноутбук","kind":"laptop","manufacturer":"Lenovo","model":"ThinkPad T14",
                 "serial":"SN-%s","purchasedOn":"2026-01-15","warrantyTo":"2029-01-15","serviceLifeMonths":36,
                 "cost":95000.00}""".formatted(tag, tag);
    }

    private String create(String tag) throws Exception {
        return JsonPath.read(body(mvc.perform(as(post("/api/v1/assets"), manager, "AST").content(asset(tag)))
                .andExpect(status().isCreated())), "$.id");
    }

    private ResultActions action(String id, String action, String json) throws Exception {
        String etag = mvc.perform(as(get("/api/v1/assets/" + id), manager, "AST")).andReturn().getResponse()
                .getHeader("ETag");
        return mvc.perform(as(post("/api/v1/assets/" + id + "/" + action), manager, "AST").header("If-Match", etag)
                .content(json));
    }

    private long notifications(String user) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "select count(*) from notification where user_id = cast(:u as uuid) and kind = 'assets'")
                .param("u", user).query(Long.class).single());
    }

    private void user(String tenant, String id) {
        TenantContext.runAs(UUID.fromString(tenant), () -> jdbc.sql("""
                        insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                        values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                        on conflict (id) do nothing""")
                .param("id", id).param("t", tenant).param("login", "a" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    private String group(String member) {
        String group = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            jdbc.sql("""
                            insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                            values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + group).update();
            jdbc.sql("""
                            insert into group_member (tenant_id, group_id, user_id)
                            values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                    .param("t", DEMO).param("g", group).param("u", member).update();
        });
        return group;
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return as(req, user, roles, DEMO);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles,
                                                    String tenant) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
