package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTransition;

/**
 * Мониторинг: права и изоляция настроек, вход по токену, дедупликация, рост важности, возврат в работу,
 * автозакрытие и журнал событий. Отпечатки уникальны для каждого теста.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TicketQueries tickets;
    @Autowired
    TicketCommands commands;

    final String admin = Ids.newId().toString();
    final String marker = Ids.newId().toString().substring(24);

    // ------------------------------------------------------------------ настройки

    @Test
    void onlyAdminManagesSourcesAuditorReadsOtherTenantDoesNotSee() throws Exception {
        String body = "{\"name\":\"Права " + marker + "\",\"kind\":\"zabbix\"}";
        perform(as(post("/api/v1/integrations/monitoring/sources"), "AGT").content(body))
                .andExpect(status().isForbidden());
        perform(as(post("/api/v1/integrations/monitoring/sources"), "AUD").content(body))
                .andExpect(status().isForbidden());
        perform(as(get("/api/v1/integrations/monitoring/sources"), "REQ")).andExpect(status().isForbidden());

        String created = body(perform(asAdmin(post("/api/v1/integrations/monitoring/sources")).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.startsWith(Tokens.PREFIX)))
                .andExpect(jsonPath("$.source.source.enabled").value(true))
                .andExpect(jsonPath("$.source.source.minSeverity").value("warning"))
                .andExpect(jsonPath("$.source.source.severityMap.critical.impact").value("high"))
                .andExpect(jsonPath("$.source.requesterName").value("Мониторинг: Права " + marker)));
        String id = JsonPath.read(created, "$.source.source.id");
        String token = JsonPath.read(created, "$.token");
        // Токен в списке не показывается, только подсказка из последних символов.
        String list = body(perform(as(get("/api/v1/integrations/monitoring/sources"), "AUD"))
                .andExpect(status().isOk()));
        assertThat(list).doesNotContain(token).contains(token.substring(token.length() - 4));

        perform(inOther(get("/api/v1/integrations/monitoring/sources/" + id))).andExpect(status().isNotFound());
        perform(inOther(get("/api/v1/integrations/monitoring/sources")))
                .andExpect(jsonPath("$.items[?(@.source.id == '" + id + "')]").isEmpty());

        perform(asAdmin(patch("/api/v1/integrations/monitoring/sources/" + id)).content("{\"enabled\":false}"))
                .andExpect(status().is(428));
        perform(asAdmin(patch("/api/v1/integrations/monitoring/sources/" + id)).header("If-Match", "\"0\"")
                .content("{\"minSeverity\":\"bad\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_source"));
        perform(asAdmin(patch("/api/v1/integrations/monitoring/sources/" + id)).header("If-Match", "\"0\"")
                .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source.enabled").value(false));

        // Выключенный источник не принимает события.
        perform(event(token, zabbix("1", "PROBLEM", "High"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("source_disabled"));

        // Перевыпуск: старый токен больше не принимается.
        String rotated = body(perform(asAdmin(post("/api/v1/integrations/monitoring/sources/" + id + "/token")))
                .andExpect(status().isOk()));
        String newToken = JsonPath.read(rotated, "$.token");
        assertThat(newToken).isNotEqualTo(token);
        perform(event(token, zabbix("1", "PROBLEM", "High"))).andExpect(status().isUnauthorized());

        perform(asAdmin(delete("/api/v1/integrations/monitoring/sources/" + id))).andExpect(status().isNoContent());
        perform(asAdmin(get("/api/v1/integrations/monitoring/sources/" + id))).andExpect(status().isNotFound());
        perform(asAdmin(get("/api/v1/admin/config-versions").param("entity", MonitoringService.HISTORY_ENTITY)
                .param("entityId", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
    }

    @Test
    void eventsWithoutValidTokenAreRejected() throws Exception {
        perform(post("/api/v1/integrations/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        perform(event("eitsm_mon_unknown", "{}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    // ------------------------------------------------------------------ Zabbix

    @Test
    void zabbixProblemCreatesOneIncidentRepeatsAreDeduplicatedRecoveryNotesIt() throws Exception {
        Source s = source("zabbix", "");
        perform(event(s.token, zabbix("10", "PROBLEM", "High"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results[0].outcome").value("created"));
        String number = ticketOf(s.id);
        TicketSnapshot t = inDemo(() -> tickets.findByNumber(number)).orElseThrow();
        assertThat(t.channel()).isEqualTo(Channel.MONITORING);
        assertThat(t.impact()).isEqualTo(Level.HIGH);
        assertThat(t.urgency()).isEqualTo(Level.MEDIUM);
        assertThat(t.title()).isEqualTo("db-" + marker + ": Нет связи " + marker);
        assertThat(t.requesterId()).isEqualTo(s.requester);

        // Повтор того же триггера (обновление, новый event_id) — тот же инцидент.
        perform(event(s.token, zabbix("11", "UPDATE", "High"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results[0].outcome").value("updated"))
                .andExpect(jsonPath("$.results[0].ticket").value(number));
        assertThat(ticketCount(s.requester)).isEqualTo(1);

        // Новый инцидент без исполнителя решить нельзя — остаётся заметка, алерт закрывается.
        perform(event(s.token, zabbix("10", "RESOLVED", "High"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.results[0].outcome").value("resolved"))
                .andExpect(jsonPath("$.results[0].detail").value(org.hamcrest.Matchers.containsString("заметка")));
        assertThat(inDemo(() -> tickets.findByNumber(number)).orElseThrow().statusCategory())
                .isEqualTo(StatusCategory.NEW);
        assertThat(notes(t.id())).anyMatch(b -> b.startsWith("Мониторинг: проблема устранена"));

        // Повтор «устранено» — нечего закрывать.
        perform(event(s.token, zabbix("10", "RESOLVED", "High")))
                .andExpect(jsonPath("$.results[0].outcome").value("ignored"));

        // Проблема снова — новый инцидент: прежний алерт закрыт.
        perform(event(s.token, zabbix("12", "PROBLEM", "High")))
                .andExpect(jsonPath("$.results[0].outcome").value("created"));
        assertThat(ticketCount(s.requester)).isEqualTo(2);

        String journal = body(perform(asAdmin(get("/api/v1/integrations/monitoring/events")
                .param("sourceId", s.id.toString()))).andExpect(status().isOk()));
        assertThat((List<String>) JsonPath.read(journal, "$.items[*].outcome"))
                .containsExactly("created", "ignored", "resolved", "updated", "created");
    }

    @Test
    void recoveryResolvesIncidentInWorkAndRepeatAfterManualResolveReopensIt() throws Exception {
        Source s = source("zabbix", "");
        perform(event(s.token, zabbix("20", "PROBLEM", "Disaster"))).andExpect(status().isAccepted());
        TicketSnapshot t = inDemo(() -> tickets.findByNumber(ticketOf(s.id))).orElseThrow();
        takeInWork(t.id());

        perform(event(s.token, zabbix("20", "RESOLVED", "Disaster")))
                .andExpect(jsonPath("$.results[0].outcome").value("resolved"))
                .andExpect(jsonPath("$.results[0].detail").value("инцидент решён"));
        TicketSnapshot resolved = inDemo(() -> tickets.find(t.id())).orElseThrow();
        assertThat(resolved.statusCategory()).isEqualTo(StatusCategory.RESOLVED);

        // Новая проблема, агент решил инцидент вручную, а алерт пришёл снова — инцидент возвращается в работу.
        perform(event(s.token, zabbix("21", "PROBLEM", "Disaster")))
                .andExpect(jsonPath("$.results[0].outcome").value("created"));
        TicketSnapshot second = inDemo(() -> tickets.findByNumber(ticketOf(s.id))).orElseThrow();
        takeInWork(second.id());
        inDemo(() -> commands.transition(second.id(), new TicketTransition("resolved", null, null, "fixed",
                "Перезагрузили"), "test"));
        perform(event(s.token, zabbix("22", "PROBLEM", "Disaster")))
                .andExpect(jsonPath("$.results[0].outcome").value("reopened"))
                .andExpect(jsonPath("$.results[0].ticket").value(second.number()));
        assertThat(inDemo(() -> tickets.find(second.id())).orElseThrow().statusCategory())
                .isEqualTo(StatusCategory.IN_PROGRESS);

        // Закрытый вручную инцидент не принимает повтор: создаётся новый.
        inDemo(() -> jdbc.sql("update ticket set status = 'closed', status_category = 'closed' where id = :id")
                .param("id", second.id()).update());
        perform(event(s.token, zabbix("23", "PROBLEM", "Disaster")))
                .andExpect(jsonPath("$.results[0].outcome").value("created"));
    }

    // ------------------------------------------------------------------ Alertmanager

    @Test
    void alertmanagerGroupCreatesIncidentPerAlertEscalatesAndIgnoresLowSeverity() throws Exception {
        Source s = source("alertmanager", "");
        String group = alertmanager("firing", List.of(
                amAlert("a-" + marker, "firing", "warning", "Медленные ответы " + marker),
                amAlert("b-" + marker, "firing", "info", "Информация " + marker)));
        String res = body(perform(event(s.token, group)).andExpect(status().isAccepted()));
        assertThat((List<String>) JsonPath.read(res, "$.results[*].outcome")).containsExactly("created", "ignored");
        String number = JsonPath.read(res, "$.results[0].ticket");
        TicketSnapshot t = inDemo(() -> tickets.findByNumber(number)).orElseThrow();
        assertThat(t.impact()).isEqualTo(Level.MEDIUM);

        // Тот же алерт стал критическим: влияние и срочность растут, в ленте заметка.
        perform(event(s.token, alertmanager("firing", List.of(
                amAlert("a-" + marker, "firing", "critical", "Медленные ответы " + marker)))))
                .andExpect(jsonPath("$.results[0].outcome").value("escalated"));
        TicketSnapshot escalated = inDemo(() -> tickets.find(t.id())).orElseThrow();
        assertThat(escalated.impact()).isEqualTo(Level.HIGH);
        assertThat(escalated.urgency()).isEqualTo(Level.HIGH);
        assertThat(notes(t.id())).anyMatch(b -> b.startsWith("Мониторинг: важность выросла до «Критическая»"));

        // Активные алерты источника.
        perform(asAdmin(get("/api/v1/integrations/monitoring/sources/" + s.id + "/alerts")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].severity").value("critical"))
                .andExpect(jsonPath("$.items[0].occurrences").value(2));
    }

    @Test
    void brokenBodyIsJournaledAndConcurrentDuplicatesCreateOneIncident() throws Exception {
        Source s = source("generic", "");
        perform(event(s.token, "не json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_event"));
        perform(asAdmin(get("/api/v1/integrations/monitoring/events").param("sourceId", s.id.toString())
                .param("outcome", "error")))
                .andExpect(jsonPath("$.items.length()").value(1));

        String same = "{\"fingerprint\":\"race-" + marker + "\",\"title\":\"Гонка " + marker
                + "\",\"severity\":\"critical\"}";
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> perform(event(s.token, same)).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(202);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(ticketCount(s.requester)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ помощники

    record Source(UUID id, String token, UUID requester) {
    }

    Source source(String kind, String extra) throws Exception {
        String created = body(perform(asAdmin(post("/api/v1/integrations/monitoring/sources"))
                .content("{\"name\":\"" + kind + " " + marker + "\",\"kind\":\"" + kind + "\"" + extra + "}"))
                .andExpect(status().isCreated()));
        return new Source(UUID.fromString(JsonPath.read(created, "$.source.source.id")), JsonPath.read(created, "$.token"),
                UUID.fromString(JsonPath.read(created, "$.source.source.requesterId")));
    }

    String zabbix(String eventId, String status, String severity) {
        return "{\"event_id\":\"" + eventId + "\",\"trigger_id\":\"t" + marker + "\",\"status\":\"" + status
                + "\",\"severity\":\"" + severity + "\",\"name\":\"Нет связи " + marker + "\",\"host\":\"db-" + marker
                + "\",\"message\":\"Узел не отвечает\"}";
    }

    static String alertmanager(String status, List<String> alerts) {
        return "{\"version\":\"4\",\"status\":\"" + status + "\",\"alerts\":[" + String.join(",", alerts) + "]}";
    }

    static String amAlert(String fingerprint, String status, String severity, String summary) {
        return "{\"status\":\"" + status + "\",\"fingerprint\":\"" + fingerprint + "\",\"labels\":{\"alertname\":\"X\","
                + "\"severity\":\"" + severity + "\"},\"annotations\":{\"summary\":\"" + summary + "\"}}";
    }

    void takeInWork(UUID ticketId) throws Exception {
        String agent = Ids.newId().toString();
        inDemo(() -> commands.change(ticketId, new TicketChange(null, null, null, null, UUID.fromString(agent), false,
                "тест")));
        inDemo(() -> commands.transition(ticketId, new TicketTransition("in_progress", null, null, null, null), "test"));
    }

    String ticketOf(UUID sourceId) throws Exception {
        return inDemo(() -> jdbc.sql("""
                select ticket_number from integration_alert where source_id = :s order by created_at desc, id desc limit 1""")
                .param("s", sourceId).query(String.class).single());
    }

    int ticketCount(UUID requester) throws Exception {
        return inDemo(() -> jdbc.sql("select count(*) from ticket where requester_id = :r").param("r", requester)
                .query(Integer.class).single());
    }

    List<String> notes(UUID ticketId) throws Exception {
        return inDemo(() -> jdbc.sql("select body from ticket_activity where ticket_id = :t and body is not null")
                .param("t", ticketId).query(String.class).list());
    }

    MockHttpServletRequestBuilder event(String token, String body) {
        return post("/api/v1/integrations/events").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    <T> T inDemo(Callable<T> action) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), action);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String roles) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, "ADM");
    }

    MockHttpServletRequestBuilder inOther(MockHttpServletRequestBuilder req) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "ADM")
                .contentType(MediaType.APPLICATION_JSON);
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
