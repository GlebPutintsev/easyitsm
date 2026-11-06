package ru.easyitsm.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.reporting.events.ReportExported;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Дашборды, провал, права и выгрузки по витрине. Факты кладутся напрямую через {@link FactStore} с заданным
 * временем (так проверяются MTTA, MTTR, SLA и повторы), каждый тест — в своей новой группе, период — январь 2026.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class ReportingApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";
    static final String PERIOD = "from=2026-01-01&to=2026-01-31&tz=UTC";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    FactStore facts;
    @Autowired
    ApplicationEvents events;

    final String analyst = Ids.newId().toString();
    final String agent = Ids.newId().toString();
    final String outsider = Ids.newId().toString();
    final UUID requester = Ids.newId();
    final UUID service = Ids.newId();
    UUID group;

    @BeforeEach
    void setUp() {
        user(agent);
        user(outsider);
        group = newGroup(agent);
    }

    @Test
    void overviewCountsMetricsInPeriodAndGroup() throws Exception {
        // Решена за 4 ч, реакция через 1 ч, SLA выполнен, оценка 5.
        UUID a = fact("Принтер", t("2026-01-10T08:00:00Z"), t("2026-01-10T09:00:00Z"), t("2026-01-10T12:00:00Z"),
                StatusCategory.CLOSED, Priority.P2, false);
        sla(a, null);
        runAs(() -> facts.applySurvey(a, 5, t("2026-01-11T10:00:00Z")));
        // Решена за 10 ч, реакция через 3 ч, SLA решения нарушен, повторное открытие, оценка 2.
        UUID b = fact("Почта", t("2026-01-12T08:00:00Z"), t("2026-01-12T11:00:00Z"), t("2026-01-12T18:00:00Z"),
                StatusCategory.RESOLVED, Priority.P1, false);
        sla(b, t("2026-01-12T16:00:00Z"));
        runAs(() -> jdbc.sql("update reporting_ticket_fact set reopened_count = 1 where ticket_id = :id")
                .param("id", b).update());
        runAs(() -> facts.applySurvey(b, 2, t("2026-01-13T10:00:00Z")));
        // Ещё одно обращение того же заявителя по той же услуге; открыта.
        fact("Почта снова", t("2026-01-13T08:00:00Z"), null, null, StatusCategory.IN_PROGRESS, Priority.P3, false);
        // Вне периода: не входит в «поступило», но открыта сейчас.
        fact("Старая", t("2025-12-01T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P4, false);

        String json = body(asAnalyst(get("/api/v1/reports/dashboards/overview?" + PERIOD + "&groupId=" + group))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Сводка"))
                .andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.allTickets").value(true)));
        assertThat(kpi(json, "created")).isEqualTo((double) (3));
        assertThat(kpi(json, "resolved")).isEqualTo((double) (2));
        assertThat(kpi(json, "backlog")).isEqualTo((double) (2));
        assertThat(kpi(json, "mtta")).isEqualTo((double) (2 * 3600));
        assertThat(kpi(json, "mttr")).isEqualTo((double) (7 * 3600));
        assertThat(kpi(json, "slaCompliance")).isEqualTo((double) (50.0));
        assertThat(kpi(json, "reopenRate")).isEqualTo((double) (50.0));
        assertThat(kpi(json, "repeats")).isEqualTo((double) (2)); // 12-го и 13-го: та же услуга в течение 7 дней
        assertThat(kpi(json, "csat")).isEqualTo((double) (3.5));
        List<Map<String, Object>> volume = JsonPath.read(json, "$.widgets[?(@.id=='overview.volume')].rows[*]");
        assertThat(volume).hasSize(31);
        assertThat(volume.get(9)).containsEntry("bucket", "2026-01-10").containsEntry("created", 1)
                .containsEntry("resolved", 1);

        String sla = body(asAnalyst(get("/api/v1/reports/dashboards/sla?" + PERIOD + "&groupId=" + group)));
        assertThat(kpi(sla, "breaches")).isEqualTo((double) (1));
        assertThat(kpi(sla, "resolutionCompliance")).isEqualTo((double) (50.0));
        List<Object> p1 = JsonPath.read(sla, "$.widgets[?(@.id=='sla.byPriority')].rows[?(@.key=='P1')].breached");
        assertThat(p1).containsExactly(1);

        String sat = body(asAnalyst(get("/api/v1/reports/dashboards/satisfaction?" + PERIOD + "&groupId=" + group)));
        assertThat(kpi(sat, "responses")).isEqualTo((double) (2));
        assertThat(kpi(sat, "csatPercent")).isEqualTo((double) (50.0));

        String groups = body(asAnalyst(get("/api/v1/reports/dashboards/groups?" + PERIOD + "&groupId=" + group)));
        assertThat((List<Object>) JsonPath.read(groups, "$.widgets[0].rows[*].label")).containsExactly("Группа " + group);
        String services = body(asAnalyst(get("/api/v1/reports/dashboards/services?" + PERIOD + "&groupId=" + group)));
        assertThat((Integer) JsonPath.read(services, "$.widgets[0].rows[0].repeats")).isEqualTo(2);

        String queue = body(asAnalyst(get("/api/v1/reports/dashboards/queue?" + PERIOD + "&groupId=" + group)));
        assertThat(kpi(queue, "open")).isEqualTo((double) (2));
        List<Object> agedOld = JsonPath.read(queue, "$.widgets[?(@.id=='queue.aging')].rows[?(@.key=='gt30d')].count");
        assertThat(agedOld).containsExactly(2); // обе открытые старше 30 дней от сегодняшнего дня
    }

    @Test
    void drillDownListsTicketsWithoutConfidentialAndPages() throws Exception {
        fact("Первая", t("2026-01-05T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P3, false);
        fact("Вторая", t("2026-01-06T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P3, false);
        fact("Секретная", t("2026-01-07T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P3, true);

        String page1 = body(asAnalyst(get("/api/v1/reports/tickets?" + PERIOD + "&groupId=" + group
                + "&metric=created&limit=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Вторая"))
                .andExpect(jsonPath("$.items[0].groupName").value("Группа " + group)));
        String cursor = JsonPath.read(page1, "$.nextCursor");
        asAnalyst(get("/api/v1/reports/tickets?" + PERIOD + "&groupId=" + group + "&metric=created&limit=1&cursor="
                + cursor)).andExpect(jsonPath("$.items[0].title").value("Первая"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        asAnalyst(get("/api/v1/reports/tickets?" + PERIOD + "&groupId=" + group + "&metric=created&date=2026-01-06"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Вторая"));
        asAnalyst(get("/api/v1/reports/tickets?" + PERIOD + "&groupId=" + group + "&metric=bogus"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_metric"));
    }

    @Test
    void agentSeesOnlyOwnGroupsAndAdminSeesNumbersOnly() throws Exception {
        fact("Своя", t("2026-01-05T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P3, false);

        // Агент группы видит её показатели и заявки.
        perform(as(get("/api/v1/reports/dashboards/overview?" + PERIOD), agent, "AGT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allTickets").value(false))
                .andExpect(jsonPath("$.canDrillDown").value(true));
        perform(as(get("/api/v1/reports/tickets?" + PERIOD + "&metric=created"), agent, "AGT"))
                .andExpect(jsonPath("$.items[?(@.title=='Своя')]").isNotEmpty());
        // Агент другой группы: чужая группа — 404, без фильтра заявки группы не видны.
        perform(as(get("/api/v1/reports/dashboards/overview?" + PERIOD + "&groupId=" + group), outsider, "AGT"))
                .andExpect(status().isNotFound());
        perform(as(get("/api/v1/reports/tickets?" + PERIOD + "&metric=created"), outsider, "AGT"))
                .andExpect(jsonPath("$.items[?(@.title=='Своя')]").isEmpty());
        // Администратор: числа по всему арендатору, но без списка заявок.
        String adm = Ids.newId().toString();
        String json = body(perform(as(get("/api/v1/reports/dashboards/overview?" + PERIOD + "&groupId=" + group),
                adm, "ADM")).andExpect(status().isOk()).andExpect(jsonPath("$.canDrillDown").value(false)));
        assertThat(kpi(json, "created")).isEqualTo((double) (1));
        perform(as(get("/api/v1/reports/tickets?" + PERIOD + "&metric=created"), adm, "ADM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("drill_down_forbidden"));
        // Заявитель в отчёты не попадает.
        perform(as(get("/api/v1/reports/dashboards"), Ids.newId().toString(), "REQ")).andExpect(status().isForbidden());
        // Другой арендатор не видит фактов.
        String other = body(mvc.perform(get("/api/v1/reports/dashboards/overview?" + PERIOD + "&groupId=" + group)
                .header("X-Dev-User", analyst).header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "ANL")));
        assertThat(kpi(other, "created")).isEqualTo((double) (0));
    }

    @Test
    void exportsAreFormulaSafeAndJournaled() throws Exception {
        fact("=HYPERLINK(\"http://evil\";\"x\")", t("2026-01-05T08:00:00Z"), null, null, StatusCategory.NEW,
                Priority.P1, false);
        fact("+cmd|' /C calc'!A0", t("2026-01-06T08:00:00Z"), null, null, StatusCategory.NEW, Priority.P2, false);

        byte[] csv = export("/api/v1/reports/tickets/export", "csv", "\"metric\":\"created\"")
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿");
        assertThat(text).contains("\"'=HYPERLINK(\"\"http://evil\"\";\"\"x\"\")\"");
        assertThat(text).contains("'+cmd|' /C calc'!A0");
        assertThat(text).doesNotContain(";=HYPERLINK").doesNotContain(";+cmd");

        byte[] xlsx = export("/api/v1/reports/tickets/export", "xlsx", "\"metric\":\"created\"")
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = zipEntry(xlsx, "xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("'=HYPERLINK(&quot;http://evil&quot;").contains("'+cmd|");
        assertThat(sheet).doesNotContain("<f>");

        byte[] pdf = export("/api/v1/reports/dashboards/overview/export", "pdf", "\"lang\":\"ru\"")
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        byte[] dashXlsx = export("/api/v1/reports/dashboards/sla/export", "xlsx", "\"lang\":\"en\"")
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(zipEntry(dashXlsx, "xl/workbook.xml")).contains("SLA compliance").contains("SLA by group");

        List<ReportExported> exported = events.stream(ReportExported.class)
                .filter(e -> e.actorId().toString().equals(analyst)).toList();
        assertThat(exported).extracting(ReportExported::report)
                .containsExactly("tickets", "tickets", "dashboard:overview", "dashboard:sla");
        assertThat(exported.get(0).rowCount()).isEqualTo(2);
        assertThat(exported.get(0).filters()).containsEntry("groupId", group.toString()).containsEntry("metric", "created");

        // Журнал видят SEC и AUD, аналитик — нет.
        String journal = body(perform(as(get("/api/v1/reports/exports?limit=200"), Ids.newId().toString(), "AUD"))
                .andExpect(status().isOk()));
        List<String> mine = JsonPath.read(journal, "$.items[?(@.createdBy=='" + analyst + "')].format");
        assertThat(mine).containsExactlyInAnyOrder("csv", "xlsx", "pdf", "xlsx");
        asAnalyst(get("/api/v1/reports/exports")).andExpect(status().isForbidden());
        export("/api/v1/reports/tickets/export", "docx", "\"metric\":\"created\"").andExpect(status().isBadRequest());
    }

    @Test
    void largeTicketExportNeedsReason() throws Exception {
        runAs(() -> jdbc.sql("""
                insert into reporting_ticket_fact (ticket_id, tenant_id, number, type, title, status_code,
                    status_category, priority, group_id, created_at, ticket_version, updated_at)
                select gen_random_uuid(), cast(:t as uuid), 'INC-' || g, 'incident', 'Массовая ' || g, 'new', 'new',
                    'P4', :group, timestamptz '2026-01-15T10:00:00Z', 0, now()
                from generate_series(1, 1001) g""").param("t", DEMO).param("group", group).update());
        export("/api/v1/reports/tickets/export", "csv", "\"metric\":\"created\"")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("export_reason_required"));
        export("/api/v1/reports/tickets/export", "pdf", "\"metric\":\"created\",\"reason\":\"Разбор за январь\"")
                .andExpect(status().isOk());
        assertThat(events.stream(ReportExported.class).filter(e -> e.actorId().toString().equals(analyst)))
                .singleElement().satisfies(e -> {
                    assertThat(e.reason()).isEqualTo("Разбор за январь");
                    assertThat(e.rowCount()).isEqualTo(1001);
                });
    }

    @Test
    void ticketEventsFillFacts() throws Exception {
        String json = body(mvc.perform(post("/api/v1/tickets").header("X-Dev-User", requester.toString())
                .header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", "REQ").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"incident\",\"title\":\"Не печатает\",\"description\":\"С утра\"}"))
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        Map<String, Object> row = TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "select number, status_category, type, requester_id from reporting_ticket_fact where ticket_id = :id")
                .param("id", id).query().singleRow());
        assertThat(row).containsEntry("number", JsonPath.read(json, "$.number")).containsEntry("status_category", "new")
                .containsEntry("type", "incident").containsEntry("requester_id", requester);
    }

    // ---------------------------------------------------------------- помощники

    private UUID fact(String title, Instant created, Instant firstResponse, Instant resolved, StatusCategory category,
                      Priority priority, boolean confidential) {
        UUID id = Ids.newId();
        TicketSnapshot s = new TicketSnapshot(id, "INC-" + id.toString().substring(30), TicketType.INCIDENT, title,
                category.code(), category, priority, Level.LOW, Level.LOW, requester, null, null, group, null, service,
                null, null, null, Channel.PORTAL, confidential, null, null, created, firstResponse, resolved,
                category == StatusCategory.CLOSED ? resolved : null, 1);
        runAs(() -> facts.applyTicket(s, created, false));
        return id;
    }

    private void sla(UUID ticket, Instant resolutionBreach) {
        runAs(() -> {
            if (resolutionBreach == null) {
                jdbc.sql("update reporting_ticket_fact set has_sla = true where ticket_id = :id").param("id", ticket)
                        .update();
            } else {
                facts.applyBreach(Ids.newId(), ticket, "sla", "resolution", "P1", resolutionBreach);
            }
        });
    }

    private static double kpi(String json, String key) {
        List<Number> v = JsonPath.read(json, "$.widgets[0].rows[?(@.key=='" + key + "')].value");
        return v.get(0).doubleValue();
    }

    private ResultActions export(String url, String format, String extra) throws Exception {
        String body = "{\"from\":\"2026-01-01\",\"to\":\"2026-01-31\",\"tz\":\"UTC\",\"groupId\":\"" + group
                + "\",\"format\":\"" + format + "\"," + extra + "}";
        return perform(as(post(url), analyst, "ANL").content(body));
    }

    private static String zipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Нет " + name);
    }

    private static Instant t(String iso) {
        return Instant.parse(iso);
    }

    private void runAs(Runnable r) {
        TenantContext.runAs(UUID.fromString(DEMO), r);
    }

    private void user(String id) {
        runAs(() -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", DEMO).param("login", "r" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    private UUID newGroup(String... members) {
        UUID g = Ids.newId();
        runAs(() -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (:id, cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", g).param("t", DEMO).param("name", "Группа " + g).update());
        for (String m : members) {
            runAs(() -> jdbc.sql("""
                    insert into group_member (tenant_id, group_id, user_id)
                    values (cast(:t as uuid), :g, cast(:u as uuid))""")
                    .param("t", DEMO).param("g", g).param("u", m).update());
        }
        return g;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions asAnalyst(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(as(req, analyst, "ANL"));
    }

    private ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
