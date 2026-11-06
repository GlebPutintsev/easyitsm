package ru.easyitsm.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

/**
 * Конфликты и заморозки (CHG-05, AT-06), стандартные изменения по шаблону и из CI/CD (CHG-08, CHG-10),
 * экстренные с последующим согласованием (CHG-12), CAB (CHG-07).
 */
class ChangeScheduleTest extends ChangeApiSupport {

    @BeforeEach
    void people() {
        users(author, manager, manager2, member);
    }

    @Test
    void overlappingChangeOnSameServiceIsShownAndMustBeAcknowledged() throws Exception {
        String first = readyNormal("Первое изменение");
        toApproval(first, LOW_RISK);
        String second = readyNormal("Второе изменение");

        String conflicts = body(perform(as(get("/api/v1/changes/" + second + "/conflicts"), author, "AGT"))
                .andExpect(status().isOk()));
        List<String> ids = read(conflicts, "$[?(@.kind == 'service')].changeId");
        assertThat(ids).contains(first);

        // AT-06: на согласование с пересечением — только с подтверждением.
        action(second, "/submit", author, "AGT", null).andExpect(status().isOk());
        perform(change(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/changes/" + second + "/risk"), second, manager, "CHG").content("{\"answers\":" + LOW_RISK + "}"))
                .andExpect(status().isOk());
        action(second, "/send-to-approval", manager, "CHG", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("change_conflicts"))
                .andExpect(jsonPath("$.conflicts[0].changeId").value(first));
        action(second, "/send-to-approval", manager, "CHG", "{\"acknowledgeConflicts\":\"Разные узлы кластера\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approval"));

        // Проверка до создания: то же окно и услуга.
        perform(as(post("/api/v1/changes/conflicts/check"), author, "AGT").content("""
                {"windowStart":"%s","windowEnd":"%s","serviceIds":["%s"]}""".formatted(windowStart, windowEnd, service)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void freezeBlocksApprovalAndShowsInCalendar() throws Exception {
        String freeze = body(perform(as(post("/api/v1/change-freezes"), manager, "CHG").content("""
                {"title":"Годовой отчёт","reason":"Закрытие года","startsAt":"%s","endsAt":"%s","scope":"targeted",
                 "serviceIds":["%s"]}""".formatted(windowStart.minus(1, ChronoUnit.DAYS),
                windowEnd.plus(1, ChronoUnit.DAYS), service))).andExpect(status().isCreated()));
        perform(as(post("/api/v1/change-freezes"), author, "AGT").content("""
                {"title":"Своя заморозка","startsAt":"%s","endsAt":"%s","scope":"all"}"""
                .formatted(windowStart, windowEnd))).andExpect(status().isForbidden());
        String id = readyNormal("Изменение в заморозку");
        action(id, "/submit", author, "AGT", null).andExpect(status().isOk());
        perform(change(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/changes/" + id + "/risk"), id, manager, "CHG").content("{\"answers\":" + LOW_RISK + "}"))
                .andExpect(status().isOk());
        action(id, "/send-to-approval", manager, "CHG", "{\"acknowledgeConflicts\":\"Срочно\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("change_freeze"));

        String calendar = body(perform(as(get("/api/v1/changes/calendar?from=" + windowStart.minus(2, ChronoUnit.DAYS)
                + "&to=" + windowEnd.plus(2, ChronoUnit.DAYS)), member, "AGT")).andExpect(status().isOk()));
        List<String> freezes = read(calendar, "$.freezes[*].id");
        assertThat(freezes).contains((String) read(freeze, "$.id"));
        List<String> changes = read(calendar, "$.changes[*].id");
        assertThat(changes).contains(id);
        perform(as(get("/api/v1/changes/calendar?from=" + windowStart + "&to=" + windowStart.plus(200, ChronoUnit.DAYS)),
                member, "AGT")).andExpect(status().isBadRequest());
    }

    @Test
    void standardChangeFromPreapprovedTemplateIsScheduledAtOnce() throws Exception {
        String key = "deploy-" + Ids.newId().toString().substring(24);
        // Стандартный шаблон без предварительного утверждения не бывает.
        perform(as(post("/api/v1/change-templates"), manager, "CHG").content("""
                {"name":"Без утверждения","changeType":"standard","preapproved":false}"""))
                .andExpect(status().isBadRequest());
        String template = body(perform(as(post("/api/v1/change-templates"), manager, "CHG").content("""
                {"name":"Выкладка портала","changeType":"standard","preapproved":true,"pipelineKey":"%s",
                 "content":{"goal":"Выкладка релиза портала","justification":"Плановый релиз","impact":"Без простоя","downtimeMinutes":0},
                 "serviceIds":["%s"],
                 "steps":[{"phase":"implement","title":"Выкатить образ","durationMinutes":10},
                          {"phase":"verify","title":"Смоук-тесты","durationMinutes":5},
                          {"phase":"rollback","title":"Вернуть прошлый образ","durationMinutes":5}]}"""
                .formatted(key, service))).andExpect(status().isCreated()));
        String templateId = read(template, "$.id");

        // Стандартное без шаблона не создать.
        perform(as(post("/api/v1/changes"), author, "AGT").content("{\"type\":\"standard\",\"title\":\"Сам\"}"))
                .andExpect(status().isBadRequest());

        String created = create(author, "AGT", """
                {"templateId":"%s","title":"Релиз портала 5.1"}""".formatted(templateId));
        String id = read(created, "$.id");
        assertThat((String) read(created, "$.type")).isEqualTo("standard");
        assertThat((List<?>) read(created, "$.steps")).hasSize(3);
        perform(change(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/changes/" + id),
                id, author, "AGT").content("""
                {"windowStart":"%s","windowEnd":"%s","downtimeMinutes":0}""".formatted(windowStart, windowEnd)))
                .andExpect(status().isOk());
        action(id, "/submit", author, "AGT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("scheduled"))
                .andExpect(jsonPath("$.approvals.length()").value(0));

        // CI/CD (CHG-10): конвейер создаёт изменение по ключу шаблона и спрашивает, можно ли выкатывать.
        Instant now = Instant.now();
        String pipeline = body(perform(as(post("/api/v1/changes/pipeline"), member, "SVC").content("""
                {"templateKey":"%s","title":"Релиз портала 5.2","repository":"git.example.ru/portal","ref":"v5.2",
                 "pipelineUrl":"https://ci.example.ru/p/42","windowStart":"%s","windowEnd":"%s",
                 "serviceIds":["%s"]}""".formatted(key, now.minus(5, ChronoUnit.MINUTES), now.plus(1, ChronoUnit.HOURS),
                Ids.newId()))).andExpect(status().isCreated()));
        String pid = read(pipeline, "$.id");
        assertThat((String) read(pipeline, "$.state")).isEqualTo("scheduled");
        perform(as(get("/api/v1/changes/" + pid + "/deploy-check"), member, "SVC")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
        perform(as(get("/api/v1/changes/" + id + "/deploy-check"), member, "SVC")).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reason").value("window"));
        perform(as(post("/api/v1/changes/" + pid + "/pipeline/started"), member, "SVC").content("{}"))
                .andExpect(status().isOk());
        perform(as(post("/api/v1/changes/" + pid + "/pipeline/finished"), member, "SVC")
                .content("{\"outcome\":\"successful\",\"conclusion\":\"Выкачено\"}")).andExpect(status().isOk());
        perform(as(get("/api/v1/changes/" + pid), manager, "CHG")).andExpect(jsonPath("$.state").value("review"))
                .andExpect(jsonPath("$.outcome").value("successful"));
        // Неизвестный ключ шаблона — 400, обычный агент конвейер не вызывает.
        perform(as(post("/api/v1/changes/pipeline"), member, "SVC").content("""
                {"templateKey":"no-such-key-%s","title":"x"}""".formatted(key))).andExpect(status().isBadRequest());
        perform(as(post("/api/v1/changes/pipeline"), member, "AGT").content("""
                {"templateKey":"%s","title":"x"}""".formatted(key))).andExpect(status().isForbidden());
    }

    @Test
    void emergencyChangeIsApprovedAfterwards() throws Exception {
        String created = create(author, "AGT", """
                {"type":"emergency","title":"Устранить утечку памяти","description":"Сервис падает","serviceIds":["%s"]}"""
                .formatted(service));
        String id = read(created, "$.id");
        perform(change(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/changes/" + id),
                id, author, "AGT").content("""
                {"justification":"Простой","windowStart":"%s","windowEnd":"%s","downtimeMinutes":10}"""
                .formatted(windowStart, windowEnd))).andExpect(status().isOk());
        perform(change(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/v1/changes/" + id + "/plan"), id, author, "AGT").content("""
                {"steps":[{"phase":"implement","title":"Перезапуск с патчем","durationMinutes":10},
                          {"phase":"rollback","title":"Вернуть","durationMinutes":5}]}""")).andExpect(status().isOk());
        String submitted = body(action(id, "/submit", author, "AGT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("scheduled")));
        assertThat((String) read(submitted, "$.postApprovalDueAt")).isNotNull();
        List<Boolean> postFactum = read(submitted, "$.approvals[*].postFactum");
        assertThat(postFactum).isNotEmpty().containsOnly(true);
        // Экстренное начинают сразу, без причины вне окна.
        action(id, "/start", author, "AGT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("implementation"));
    }

    @Test
    void cabReviewsAgendaVotesAndDecides() throws Exception {
        String id = readyNormal("Миграция БД биллинга");
        String approval = toApproval(id, MEDIUM_RISK);
        List<String> kinds = read(approval, "$.approvals[*].kind");
        assertThat(kinds).containsExactlyInAnyOrder("manager", "cab");
        String cabSlot = read(approval, "$.approvals[?(@.kind == 'cab')].id", 0);
        String managerSlot = read(approval, "$.approvals[?(@.kind == 'manager')].id", 0);
        action(id, "/approvals/" + cabSlot + "/decision", manager2, "CHG", "{\"decision\":\"approve\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cab_decision_required"));
        action(id, "/approvals/" + managerSlot + "/decision", manager2, "CHG", "{\"decision\":\"approve\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("approval"));

        String meeting = body(perform(as(post("/api/v1/cab/meetings"), manager, "CHG").content("""
                {"title":"CAB четверг","startsAt":"%s","durationMinutes":60,"chairId":"%s","memberIds":["%s","%s"]}"""
                .formatted(Instant.now().plus(1, ChronoUnit.DAYS), manager, member, author)))
                .andExpect(status().isCreated()));
        String mid = read(meeting, "$.id");
        String agenda = body(perform(cab(post("/api/v1/cab/meetings/" + mid + "/collect"), mid))
                .andExpect(status().isOk()));
        String itemId = read(agenda, "$.items[?(@.change.id == '" + id + "')].id", 0);
        perform(cab(post("/api/v1/cab/meetings/" + mid + "/items"), mid).content("{\"changeId\":\"" + id + "\"}"))
                .andExpect(status().isConflict());

        // Голосовать можно заранее, решение — только на начатом заседании.
        perform(as(post("/api/v1/cab/meetings/" + mid + "/items/" + itemId + "/decision"), manager, "CHG")
                .content("{\"decision\":\"approve\"}")).andExpect(status().isConflict());
        perform(cab(post("/api/v1/cab/meetings/" + mid + "/start"), mid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_progress"));
        perform(as(post("/api/v1/cab/meetings/" + mid + "/items/" + itemId + "/vote"), member, "AGT")
                .content("{\"vote\":\"approve\",\"comment\":\"План возврата понятен\"}")).andExpect(status().isOk());
        // Автор изменения — участник, но за своё не голосует (AT-05); посторонний не голосует вовсе.
        perform(as(post("/api/v1/cab/meetings/" + mid + "/items/" + itemId + "/vote"), author, "AGT")
                .content("{\"vote\":\"approve\"}")).andExpect(status().isForbidden());
        perform(as(post("/api/v1/cab/meetings/" + mid + "/items/" + itemId + "/vote"), manager2, "AGT")
                .content("{\"vote\":\"approve\"}")).andExpect(status().isForbidden());

        perform(as(post("/api/v1/cab/meetings/" + mid + "/items/" + itemId + "/decision"), manager, "CHG")
                .content("{\"decision\":\"approve\",\"comment\":\"Утверждено\"}")).andExpect(status().isOk());
        perform(as(get("/api/v1/changes/" + id), author, "AGT")).andExpect(jsonPath("$.state").value("scheduled"));

        perform(cab(post("/api/v1/cab/meetings/" + mid + "/complete"), mid)
                .content("{\"minutes\":\"Рассмотрено одно изменение\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"));
        perform(cab(post("/api/v1/cab/meetings/" + mid + "/protocol"), mid)).andExpect(status().isOk())
                .andExpect(jsonPath("$.protocolSentAt").isNotEmpty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager tm;

    @Test
    void mergedDuplicateCiIsReplacedInChanges() throws Exception {
        String id = readyNormal("Изменение с дубликатом услуги");
        java.util.UUID survivor = Ids.newId();
        java.util.UUID tenant = java.util.UUID.fromString(DEMO);
        ru.easyitsm.cmdb.api.CiRef ref = new ru.easyitsm.cmdb.api.CiRef(survivor, "Портал", "business_service",
                "Бизнес-услуга", ru.easyitsm.cmdb.api.CiLayer.BUSINESS_SERVICE, ru.easyitsm.cmdb.api.CiStatus.ACTIVE,
                "prod", ru.easyitsm.cmdb.api.CiCriticality.HIGH, null, 0);
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(s ->
                ru.easyitsm.platform.tenant.TenantContext.runAs(tenant, () -> events.publishEvent(
                        new ru.easyitsm.cmdb.events.CiMerged(tenant, Instant.now(), null, "reconciliation", ref,
                                java.util.UUID.fromString(service)))));
        perform(as(get("/api/v1/changes/" + id), author, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceIds.length()").value(1))
                .andExpect(jsonPath("$.serviceIds[0]").value(survivor.toString()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cab(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String mid) throws Exception {
        String etag = mvc.perform(as(get("/api/v1/cab/meetings/" + mid), manager, "CHG")).andReturn().getResponse()
                .getHeader("ETag");
        return as(req, manager, "CHG").header("If-Match", etag);
    }

    private static String read(String json, String path, int index) {
        List<String> values = read(json, path);
        return values.get(index);
    }
}
