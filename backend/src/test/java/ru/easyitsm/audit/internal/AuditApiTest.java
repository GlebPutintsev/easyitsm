package ru.easyitsm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditResult;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** REST журнала аудита, права SEC/AUD, отказы в доступе, настройки, выгрузка (SEC-14, SEC-29, SEC-30, AT-01). */
class AuditApiTest extends AuditTestSupport {

    @Test
    void onlySecurityAndAuditorsReadTheLog() throws Exception {
        mvc.perform(get("/api/v1/audit/events")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "AUD")).andExpect(status().isOk());
        String agent = Ids.newId().toString();
        for (String role : List.of("AGT", "ADM", "LEAD", "REQ")) {
            mvc.perform(as(get("/api/v1/audit/events"), DEMO, agent, role)).andExpect(status().isForbidden());
        }
        // Попытка прочитать журнал без права сама попадает в журнал
        assertThat(rows(DEMO, "access.denied")).anyMatch(r -> agent.equals(String.valueOf(r.actorId()))
                && "audit/events".equals(r.objectType()) && "denied".equals(r.result()));
    }

    @Test
    void foreignTicketByUrlIs404AndRecorded() throws Exception {
        // AT-01: заявитель подставляет в адрес номер чужой заявки
        String owner = Ids.newId().toString();
        String json = mvc.perform(as(post("/api/v1/tickets"), DEMO, owner, "REQ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"incident\",\"title\":\"Моя заявка\",\"description\":\"Текст\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ticketId = JsonPath.read(json, "$.id");
        String number = JsonPath.read(json, "$.number");
        String intruder = Ids.newId().toString();

        mvc.perform(as(get("/api/v1/tickets/" + number), DEMO, intruder, "REQ")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/tickets/" + ticketId), DEMO, intruder, "REQ")).andExpect(status().isNotFound());

        List<Row> denied = rows(DEMO, "access.denied").stream()
                .filter(r -> intruder.equals(String.valueOf(r.actorId()))).toList();
        assertThat(denied).hasSize(2);
        assertThat(denied).extracting(Row::objectId).containsExactlyInAnyOrder(number, ticketId);
        assertThat(denied.getFirst().objectType()).isEqualTo("tickets/{ref}");
        assertThat(denied.getFirst().details()).contains("\"status\": 404").contains("\"method\": \"GET\"");
    }

    @Test
    void entryHasWhoWhatWhereAndTrace() throws Exception {
        String admin = Ids.newId().toString();
        String name = "Аудит " + Ids.newId();
        mvc.perform(as(post("/api/v1/ticket-settings/categories"), DEMO, admin, "ADM")
                        .header("User-Agent", "AuditTest/1.0").header("traceparent",
                                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().is2xxSuccessful());

        String page = mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "AUD")
                        .param("action", "config.change").param("actorId", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(page, "$.items")).hasSize(1);
        assertThat((String) JsonPath.read(page, "$.items[0].category")).isEqualTo("config");
        assertThat((String) JsonPath.read(page, "$.items[0].result")).isEqualTo("success");
        assertThat((String) JsonPath.read(page, "$.items[0].objectType")).isEqualTo("ticket-settings/categories");
        assertThat((String) JsonPath.read(page, "$.items[0].ip")).isNotBlank();
        assertThat((String) JsonPath.read(page, "$.items[0].userAgent")).isEqualTo("AuditTest/1.0");
        assertThat((String) JsonPath.read(page, "$.items[0].traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat((String) JsonPath.read(page, "$.items[0].details.area")).isEqualTo("tickets");
        assertThat((String) JsonPath.read(page, "$.items[0].hash")).hasSize(64);
        String id = JsonPath.read(page, "$.items[0].id");
        mvc.perform(as(get("/api/v1/audit/events/" + id), DEMO, sec, "SEC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actorId").value(admin));
    }

    @Test
    void privilegedActionIsRecorded() throws Exception {
        String admin = Ids.newId().toString();
        UUID group = Ids.newId();
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (:id, cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", group).param("t", DEMO).param("name", "Аудит " + group).update());
        String rule = mvc.perform(as(post("/api/v1/ticket-settings/routing-rules"), DEMO, admin, "ADM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Удаляемое правило","position":999,"enabled":false,
                                 "conditions":{"keywords":["аудит-%s"]},"groupId":"%s"}""".formatted(group, group)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ruleId = JsonPath.read(rule, "$.id");
        mvc.perform(as(delete("/api/v1/ticket-settings/routing-rules/" + ruleId), DEMO, admin, "ADM"))
                .andExpect(status().isNoContent());
        assertThat(rows(DEMO, "security.privileged_action"))
                .anyMatch(r -> ruleId.equals(r.objectId()) && "success".equals(r.result()));
    }

    @Test
    void listIsFilteredPagedAndIsolatedByTenant() throws Exception {
        String marker = "marker-" + Ids.newId();
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            for (int i = 0; i < 3; i++) {
                writer.record(AuditEntry.of(AuditCategory.DATA, "data.sensitive_read").object("test", marker)
                        .detail("n", i));
            }
        });
        String first = mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC")
                        .param("objectId", marker).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(first, "$.items[0].details.n")).isEqualTo(2);
        String cursor = JsonPath.read(first, "$.nextCursor");
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC").param("objectId", marker)
                        .param("cursor", cursor))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].details.n").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC").param("q", marker))
                .andExpect(jsonPath("$.items.length()").value(3));
        // Другой арендатор не видит записи ни списком, ни по id
        String id = JsonPath.read(first, "$.items[0].id");
        mvc.perform(as(get("/api/v1/audit/events"), OTHER, sec, "SEC").param("objectId", marker))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(as(get("/api/v1/audit/events/" + id), OTHER, sec, "SEC")).andExpect(status().isNotFound());
        // Неизвестные значения фильтра — 400, а не ошибка запроса
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC").param("category", "x' or 1=1--"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/audit/events"), DEMO, sec, "SEC").param("q", "%' or 1=1--"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void secretsInDetailsAreMasked() {
        String marker = "mask-" + Ids.newId();
        TenantContext.runAs(UUID.fromString(DEMO), () -> writer.record(
                AuditEntry.of(AuditCategory.AUTH, "auth.test").object("test", marker).result(AuditResult.FAILURE)
                        .detail("password", "Qwerty123!").detail("comment", "токен: abcdef123456")));
        String details = rows(DEMO, "auth.test").stream().filter(r -> marker.equals(r.objectId())).findFirst()
                .orElseThrow().details();
        assertThat(details).doesNotContain("Qwerty123!").doesNotContain("abcdef123456").contains("***");
    }

    @Test
    void exportNeedsReasonIsCsvSafeAndRecorded() throws Exception {
        String marker = "=HYPERLINK(\"http://evil\")" + Ids.newId();
        TenantContext.runAs(UUID.fromString(DEMO), () -> writer.record(
                AuditEntry.of(AuditCategory.DATA, "data.sensitive_read").object("test", marker)));

        mvc.perform(as(post("/api/v1/audit/export"), DEMO, sec, "AGT").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Проверка инцидента\"}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/audit/export"), DEMO, sec, "AUD").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("export_reason_required"));

        String csv = mvc.perform(as(post("/api/v1/audit/export"), DEMO, sec, "AUD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Запрос службы безопасности\",\"objectId\":" + quote(marker) + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"seq\";\"at\"");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil\"\")");
        assertThat(csv.lines().count()).isEqualTo(2);

        assertThat(rows(DEMO, "audit.export")).anyMatch(r -> sec.equals(String.valueOf(r.actorId()))
                && r.details().contains("Запрос службы безопасности"));
    }

    @Test
    void siemStatusIsForSecurityOnly() throws Exception {
        mvc.perform(as(get("/api/v1/audit/siem"), DEMO, sec, "SEC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.headSeq").isNumber());
        mvc.perform(as(get("/api/v1/audit/siem"), DEMO, sec, "AUD")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/audit/siem/test"), DEMO, sec, "SEC")).andExpect(status().isConflict());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void applicationLogsAreMasked() {
        // logback-spring.xml подменяет %m: проверяем на настроенном Spring Boot контексте Logback
        var context = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        var layout = new ch.qos.logback.classic.PatternLayout();
        layout.setContext(context);
        layout.setPattern("%m");
        layout.start();
        var logger = context.getLogger("test");
        var event = new ch.qos.logback.classic.spi.LoggingEvent("x", logger, ch.qos.logback.classic.Level.INFO,
                "вход {} с паролем password={}", null, new Object[] {"ivan@corp.ru", "Qwerty123"});
        assertThat(layout.doLayout(event)).isEqualTo("вход i***@corp.ru с паролем password=***");
    }
}
