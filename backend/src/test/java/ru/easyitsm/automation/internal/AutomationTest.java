package ru.easyitsm.automation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.events.TicketCreated;

/**
 * Автоматизация: права и изоляция REST, проверка правил, запуск по событиям, журнал и защита от петель (AUT-01, 04).
 * Правила срабатывают только на заявках теста: условия завязаны на уникальную метку в теме или на новые группы.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutomationTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TicketCommands commands;
    @Autowired
    TicketQueries queries;
    @Autowired
    AutomationEventsListener listener;

    final String admin = Ids.newId().toString();
    final String marker = "auto-" + Ids.newId().toString().substring(24);

    // ------------------------------------------------------------------ права и изоляция

    @Test
    void onlyAdminChangesRulesAuditorReadsOthersAreForbidden() throws Exception {
        String rule = ruleJson("Права " + marker, "ticket_created", titleContains(marker), "[" + priority("P2") + "]", false);
        perform(as(post("/api/v1/automation/rules"), Ids.newId().toString(), "AGT").content(rule))
                .andExpect(status().isForbidden());
        perform(as(get("/api/v1/automation/rules"), Ids.newId().toString(), "REQ")).andExpect(status().isForbidden());
        perform(as(get("/api/v1/automation/runs"), Ids.newId().toString(), "AGT,LEAD")).andExpect(status().isForbidden());
        perform(as(post("/api/v1/automation/rules"), Ids.newId().toString(), "AUD").content(rule))
                .andExpect(status().isForbidden());

        String created = body(perform(asAdmin(post("/api/v1/automation/rules")).content(rule))
                .andExpect(status().isCreated()).andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.enabled").value(false)));
        String id = JsonPath.read(created, "$.id");
        perform(as(get("/api/v1/automation/rules/" + id), Ids.newId().toString(), "AUD")).andExpect(status().isOk());
        perform(as(get("/api/v1/automation/runs"), Ids.newId().toString(), "AUD")).andExpect(status().isOk());
        perform(as(patch("/api/v1/automation/rules/" + id), Ids.newId().toString(), "AUD").header("If-Match", "\"0\"")
                .content("{\"enabled\":true}")).andExpect(status().isForbidden());

        // Другой арендатор не видит и не меняет правило: 404.
        perform(inOther(get("/api/v1/automation/rules/" + id))).andExpect(status().isNotFound());
        perform(inOther(delete("/api/v1/automation/rules/" + id))).andExpect(status().isNotFound());
        perform(inOther(get("/api/v1/automation/rules"))).andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isEmpty());

        // Изменение требует If-Match.
        perform(asAdmin(patch("/api/v1/automation/rules/" + id)).content("{\"enabled\":true}"))
                .andExpect(status().is(428));
        perform(asAdmin(patch("/api/v1/automation/rules/" + id)).header("If-Match", "\"0\"")
                .content("{\"enabled\":true,\"name\":\"Переименовано " + marker + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(1));
        perform(asAdmin(patch("/api/v1/automation/rules/" + id)).header("If-Match", "\"0\"")
                .content("{\"enabled\":false}")).andExpect(status().isPreconditionFailed());
        perform(asAdmin(delete("/api/v1/automation/rules/" + id))).andExpect(status().isNoContent());
        perform(asAdmin(get("/api/v1/automation/rules/" + id))).andExpect(status().isNotFound());

        // Каждое изменение правила попадает в журнал версий настроек «Управления».
        perform(asAdmin(get("/api/v1/admin/config-versions").param("entity", "automation.rule").param("entityId", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].action", containsInAnyOrder("create", "update", "delete")))
                .andExpect(jsonPath("$.items[0].label").value("Переименовано " + marker));
    }

    @Test
    void invalidRulesAreRejectedWithReason() throws Exception {
        String[][] cases = {
                {ruleJson("Без события", "nope", "null", "[" + priority("P1") + "]", true), "событие"},
                {ruleJson("Без действий", "ticket_created", "null", "[]", true), "действие"},
                {ruleJson("Чужое поле", "ticket_created", cond("fromPriority", "eq", "\"p1\""), "[" + priority("P1") + "]", true),
                        "Условие 1"},
                {ruleJson("Плохой приоритет", "ticket_created", cond("priority", "eq", "\"p9\""), "[" + priority("P1") + "]",
                        true), "недопустимое значение"},
                {ruleJson("Нет группы", "ticket_created", "null",
                        "[{\"type\":\"assign\",\"groupId\":\"" + Ids.newId() + "\"}]", true), "группа не найдена"},
                {ruleJson("Сравнение текста", "ticket_created", cond("title", "gte", "5"), "[" + priority("P1") + "]", true),
                        "только для чисел"},
                {ruleJson("Уведомление без текста", "ticket_created", "null",
                        "[{\"type\":\"notify\",\"target\":\"assignee\"}]", true), "текст уведомления"},
                {ruleJson("Уведомление никому", "ticket_created", "null",
                        "[{\"type\":\"notify\",\"target\":\"all\",\"body\":\"x\"}]", true), "получатель"},
                {ruleJson("Уведомление без пользователя", "ticket_created", "null",
                        "[{\"type\":\"notify\",\"target\":\"user\",\"body\":\"x\"}]", true), "пользователя"},
                {ruleJson("Вебхук без адреса", "ticket_created", "null", "[{\"type\":\"webhook\"}]", true),
                        "выберите вебхук"},
        };
        for (String[] c : cases) {
            String detail = JsonPath.read(body(perform(asAdmin(post("/api/v1/automation/rules")).content(c[0]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_rule"))), "$.detail");
            assertThat(detail).containsIgnoringCase(c[1]);
        }
    }

    // ------------------------------------------------------------------ запуск по событиям

    @Test
    void notifyActionReachesUserAndGroupWithTicketLink() throws Exception {
        String person = Ids.newId().toString();
        user(person);
        String member = Ids.newId().toString();
        user(member);
        String group = newGroup();
        inDemo(() -> jdbc.sql("""
                insert into group_member (group_id, user_id, tenant_id)
                values (cast(:g as uuid), cast(:u as uuid), cast(:t as uuid))""")
                .param("g", group).param("u", member).param("t", DEMO).update());
        createRule(ruleJson("Уведомить " + marker, "ticket_created", titleContains(marker),
                "[{\"type\":\"notify\",\"target\":\"user\",\"userId\":\"" + person
                        + "\",\"title\":\"Новая {{number}}\",\"body\":\"{{title}} по правилу {{rule}}\"},"
                        + "{\"type\":\"notify\",\"target\":\"group\",\"groupId\":\"" + group
                        + "\",\"body\":\"Для группы\"}]", true));
        TicketRef ref = create("Принтер " + marker);
        Map<String, Object> n = inDemo(() -> jdbc.sql("""
                select title, body, link from notification where user_id = cast(:u as uuid)""")
                .param("u", person).query().singleRow());
        assertThat(n.get("title")).isEqualTo("Новая " + ref.number());
        assertThat(n.get("body")).isEqualTo("Принтер " + marker + " по правилу Уведомить " + marker);
        assertThat((String) n.get("link")).endsWith("/agent/tickets/" + ref.number());
        // Без заголовка — номер и тема заявки.
        assertThat(inDemo(() -> jdbc.sql("select title from notification where user_id = cast(:u as uuid)")
                .param("u", member).query(String.class).single())).isEqualTo(ref.number() + ": Принтер " + marker);
        String runs = body(perform(asAdmin(get("/api/v1/automation/runs?ticket=" + ref.number()))));
        assertThat((List<String>) JsonPath.read(runs, "$.items[0].results[*].detail"))
                .containsExactly("1 получ.", "1 получ.");
    }

    @Test
    void confidentialTicketTitleIsHiddenInNotifications() throws Exception {
        TicketRef ref = create("Тайна " + marker);
        TicketSnapshot t = inDemo(() -> queries.find(ref.id())).orElseThrow();
        TicketSnapshot secret = new TicketSnapshot(t.id(), t.number(), t.type(), t.title(), t.statusCode(),
                t.statusCategory(), t.priority(), t.impact(), t.urgency(), t.requesterId(), t.createdForId(),
                t.organizationId(), t.groupId(), t.assigneeId(), t.serviceId(), t.ciId(), t.categoryId(),
                t.catalogItemId(), t.channel(), true, t.parentId(), t.dueAt(), t.createdAt(), t.firstResponseAt(),
                t.resolvedAt(), t.closedAt(), t.version());
        assertThat(ActionRunner.renderSafe("{{number}}: {{title}}", secret, "r"))
                .isEqualTo(t.number() + ": Конфиденциальная заявка");
        assertThat(ActionRunner.renderSafe("{{number}}: {{title}}", t, "r")).isEqualTo(t.number() + ": Тайна " + marker);
    }

    @Test
    void ruleRunsOnTicketCreatedAndJournalRecordsIt() throws Exception {
        String watcher = Ids.newId().toString();
        user(watcher);
        String ruleId = createRule(ruleJson("Создание " + marker, "ticket_created",
                "{\"match\":\"all\",\"items\":[" + condItem("title", "contains", "\"" + marker + "\"") + ","
                        + condItem("type", "in", "[\"incident\",\"request\"]") + "]}",
                "[" + priority("P1") + ",{\"type\":\"add_comment\",\"body\":\"Срочно: {{number}} по правилу {{rule}}\"},"
                        + "{\"type\":\"add_watcher\",\"userId\":\"" + watcher + "\"}]", true));

        TicketRef ref = create("Сервер упал " + marker);
        TicketSnapshot t = inDemo(() -> queries.find(ref.id())).orElseThrow();
        assertThat(t.priority()).isEqualTo(Priority.P1);
        String note = inDemo(() -> jdbc.sql("select body from ticket_activity where ticket_id = :t and kind = 'note'")
                .param("t", ref.id()).query(String.class).single());
        assertThat(note).isEqualTo("Срочно: " + ref.number() + " по правилу Создание " + marker);
        assertThat(inDemo(() -> jdbc.sql("select count(*) from ticket_watcher where ticket_id = :t and user_id = cast(:u as uuid)")
                .param("t", ref.id()).param("u", watcher).query(Integer.class).single())).isEqualTo(1);

        String runs = body(perform(asAdmin(get("/api/v1/automation/runs?ruleId=" + ruleId))).andExpect(status().isOk()));
        assertThat((List<?>) JsonPath.read(runs, "$.items")).hasSize(1);
        assertThat((String) JsonPath.read(runs, "$.items[0].status")).isEqualTo("success");
        assertThat((Integer) JsonPath.read(runs, "$.items[0].depth")).isEqualTo(1);
        assertThat((String) JsonPath.read(runs, "$.items[0].ticketNumber")).isEqualTo(ref.number());
        assertThat((List<String>) JsonPath.read(runs, "$.items[0].results[*].outcome"))
                .containsExactly("applied", "applied", "applied");
        // Фильтр журнала по номеру заявки.
        perform(asAdmin(get("/api/v1/automation/runs?ticket=" + ref.number())))
                .andExpect(jsonPath("$.items.length()").value(1));

        // Другая заявка без метки правило не запускает.
        TicketRef other = create("Обычная заявка " + Ids.newId());
        assertThat(inDemo(() -> queries.find(other.id())).orElseThrow().priority()).isEqualTo(Priority.P4);
    }

    @Test
    void sameEventDeliveredTwiceRunsRuleOnce() throws Exception {
        String ruleId = createRule(ruleJson("Повтор " + marker, "ticket_created", titleContains(marker),
                "[{\"type\":\"add_comment\",\"body\":\"Один раз\"}]", true));
        TicketRef ref = create("Повтор " + marker);
        TicketSnapshot t = inDemo(() -> queries.find(ref.id())).orElseThrow();
        // outbox после перезапуска доставляет событие ещё раз
        listener.on(new TicketCreated(UUID.fromString(DEMO), Instant.now(), null, withVersion(t, 0)));
        assertThat(runCount(ruleId)).isEqualTo(1);
        assertThat(inDemo(() -> jdbc.sql("select count(*) from ticket_activity where ticket_id = :t and kind = 'note'")
                .param("t", ref.id()).query(Integer.class).single())).isEqualTo(1);
    }

    @Test
    void failedActionRollsBackWholeRunAndIsJournaled() throws Exception {
        String ruleId = createRule(ruleJson("Ошибка " + marker, "ticket_created", titleContains(marker),
                "[" + priority("P1") + ",{\"type\":\"set_status\",\"status\":\"closed\"}]", true));
        TicketRef ref = create("Ошибка " + marker);
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().priority()).isEqualTo(Priority.P4);
        String runs = body(perform(asAdmin(get("/api/v1/automation/runs?status=failed&ruleId=" + ruleId))));
        assertThat((String) JsonPath.read(runs, "$.items[0].errorCode")).isEqualTo("transition_not_allowed");
        assertThat((String) JsonPath.read(runs, "$.items[0].error")).startsWith("Действие 2 (set_status)");
    }

    @Test
    void ruleLoopIsStoppedAndJournaled() throws Exception {
        // A: P1 → P2, B: P2 → P1. Цепочка A → B → A останавливается на втором A.
        String a = createRule(ruleJson("Петля A " + marker, "priority_changed",
                "{\"match\":\"all\",\"items\":[" + condItem("title", "contains", "\"" + marker + "\"") + ","
                        + condItem("priority", "eq", "\"p1\"") + "]}", "[" + priority("P2") + "]", true));
        String b = createRule(ruleJson("Петля B " + marker, "priority_changed",
                "{\"match\":\"all\",\"items\":[" + condItem("title", "contains", "\"" + marker + "\"") + ","
                        + condItem("priority", "eq", "\"p2\"") + "]}", "[" + priority("P1") + "]", true));
        TicketRef ref = create("Петля " + marker);
        inDemo(() -> commands.change(ref.id(), new TicketChange(Priority.P1, null, null, null, null, false, "тест")));

        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().priority()).isEqualTo(Priority.P1);
        List<Map<String, Object>> runs = runsOf(ref.id());
        assertThat(runs).extracting(r -> r.get("rule_id").toString(), r -> r.get("status"), r -> r.get("depth"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(a, "success", 1),
                        org.assertj.core.groups.Tuple.tuple(b, "success", 2),
                        org.assertj.core.groups.Tuple.tuple(a, "skipped", 3));
        assertThat(runs.get(2).get("skip_reason")).isEqualTo("loop");
    }

    @Test
    void chainDeeperThanFiveIsStopped() throws Exception {
        // R1..R6: группа Gi → назначить Gi+1. Шестое правило — глубина 6 — не выполняется.
        String[] groups = new String[8];
        for (int i = 0; i < groups.length; i++) {
            groups[i] = newGroup();
        }
        for (int i = 1; i <= 6; i++) {
            createRule(ruleJson("Глубина " + i + " " + marker, "assignment_changed", cond("groupId", "eq", "\"" + groups[i] + "\""),
                    "[{\"type\":\"assign\",\"groupId\":\"" + groups[i + 1] + "\"}]", true));
        }
        TicketRef ref = create("Глубина " + marker);
        inDemo(() -> commands.change(ref.id(), new TicketChange(null, null, null, UUID.fromString(groups[1]), null, false,
                "тест")));

        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().groupId()).isEqualTo(UUID.fromString(groups[6]));
        List<Map<String, Object>> runs = runsOf(ref.id());
        assertThat(runs).extracting(r -> r.get("status")).containsExactly("success", "success", "success", "success",
                "success", "skipped");
        assertThat(runs.get(5).get("skip_reason")).isEqualTo("depth_limit");
        assertThat(runs.get(5).get("depth")).isEqualTo(6);
    }

    @Test
    void slaBreachSetPriorityActionIsExecuted() throws Exception {
        TicketRef ref = create("SLA " + marker);
        listener.on(new SlaBreached(UUID.fromString(DEMO), ref.id(), Ids.newId(), Ids.newId(), "sla", "resolution", "P4",
                Instant.now(), List.of("notify:assignee", "set_priority:P1")));
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().priority()).isEqualTo(Priority.P1);
        assertThat(runsOf(ref.id())).extracting(r -> r.get("rule_name")).containsExactly("SLA: set_priority:P1");
    }

    @Test
    void testEndpointShowsWhatRuleWouldDoWithoutChanges() throws Exception {
        TicketRef ref = create("Проверка " + marker);
        String rule = ruleJson("", "status_changed",
                "{\"match\":\"all\",\"items\":[" + condItem("title", "contains", "\"" + marker + "\"") + ","
                        + condItem("fromStatusCategory", "eq", "\"new\"") + "]}",
                "[" + priority("P1") + ",{\"type\":\"add_comment\",\"body\":\"{{number}}: {{title}}\"}]", true);
        String result = body(perform(asAdmin(post("/api/v1/automation/test"))
                .content("{\"rule\":" + rule + ",\"ticket\":\"" + ref.number().toLowerCase() + "\"}"))
                .andExpect(status().isOk()));
        assertThat((Object) JsonPath.read(result, "$.matched")).isNull(); // зависит от события
        assertThat((List<Object>) JsonPath.read(result, "$.conditions")).containsExactly(true, null);
        assertThat((List<String>) JsonPath.read(result, "$.unknownFields")).containsExactly("fromStatusCategory");
        assertThat((List<String>) JsonPath.read(result, "$.actions[*].outcome")).containsExactly("change", "change");
        assertThat((String) JsonPath.read(result, "$.actions[1].detail")).isEqualTo(ref.number() + ": Проверка " + marker);
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().priority()).isEqualTo(Priority.P4);

        perform(asAdmin(post("/api/v1/automation/test")).content("{\"rule\":" + rule + ",\"ticket\":\"INC-999999999\"}"))
                .andExpect(status().isNotFound());
        perform(as(post("/api/v1/automation/test"), Ids.newId().toString(), "AUD")
                .content("{\"rule\":" + rule + ",\"ticket\":\"" + ref.number() + "\"}")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ помощники

    TicketRef create(String title) throws Exception {
        return inDemo(() -> commands.create(NewTicket.of(TicketType.INCIDENT, title, "", Ids.newId(), Channel.PORTAL)));
    }

    String createRule(String json) throws Exception {
        return JsonPath.read(body(perform(asAdmin(post("/api/v1/automation/rules")).content(json))
                .andExpect(status().isCreated())), "$.id");
    }

    int runCount(String ruleId) throws Exception {
        return inDemo(() -> jdbc.sql("select count(*) from automation_run where rule_id = cast(:r as uuid)")
                .param("r", ruleId).query(Integer.class).single());
    }

    List<Map<String, Object>> runsOf(UUID ticketId) throws Exception {
        return inDemo(() -> jdbc.sql("""
                select rule_id, rule_name, status, skip_reason, depth from automation_run
                 where ticket_id = :t order by depth, started_at, id""").param("t", ticketId).query().listOfRows());
    }

    static TicketSnapshot withVersion(TicketSnapshot t, long version) {
        return new TicketSnapshot(t.id(), t.number(), t.type(), t.title(), t.statusCode(), t.statusCategory(), t.priority(),
                t.impact(), t.urgency(), t.requesterId(), t.createdForId(), t.organizationId(), t.groupId(), t.assigneeId(),
                t.serviceId(), t.ciId(), t.categoryId(), t.catalogItemId(), t.channel(), t.confidential(), t.parentId(),
                t.dueAt(), t.createdAt(), t.firstResponseAt(), t.resolvedAt(), t.closedAt(), version);
    }

    void user(String id) throws Exception {
        inDemo(() -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", DEMO).param("login", "a" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    String newGroup() throws Exception {
        String group = Ids.newId().toString();
        inDemo(() -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", group).param("t", DEMO).param("name", "Группа " + group).update());
        return group;
    }

    static String ruleJson(String name, String trigger, String conditions, String actions, boolean enabled) {
        return "{\"name\":\"" + name + "\",\"trigger\":\"" + trigger + "\",\"conditions\":" + conditions
                + ",\"actions\":" + actions + ",\"enabled\":" + enabled + "}";
    }

    static String titleContains(String marker) {
        return cond("title", "contains", "\"" + marker + "\"");
    }

    static String cond(String field, String op, String value) {
        return "{\"match\":\"all\",\"items\":[" + condItem(field, op, value) + "]}";
    }

    static String condItem(String field, String op, String value) {
        return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + value + "}";
    }

    static String priority(String p) {
        return "{\"type\":\"set_priority\",\"priority\":\"" + p + "\"}";
    }

    <T> T inDemo(Callable<T> action) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), action);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder inOther(MockHttpServletRequestBuilder req) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "ADM")
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, admin, "ADM");
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
