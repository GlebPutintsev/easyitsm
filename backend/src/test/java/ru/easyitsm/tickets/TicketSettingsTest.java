package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

/**
 * Настраиваемые поля, маршрутизация, циклы, представления и готовые ответы. Настройки пишутся в арендатор
 * OTHER и имеют уникальные коды, чтобы не влиять на остальные тесты.
 */
class TicketSettingsTest extends TicketApiSupport {

    final String suffix = Long.toString(System.nanoTime(), 36);

    TicketSettingsTest() {
        tenant = OTHER;
    }

    @Test
    void customFieldsAreValidated() throws Exception {
        String code = "os_" + suffix;
        perform(asAdmin(post("/api/v1/ticket-settings/fields")).content("""
                {"code":"%s","name":"ОС","fieldType":"list","ticketTypes":["incident"],
                 "options":[{"value":"win","label":"Windows"},{"value":"linux","label":"Linux"}]}""".formatted(code)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(code));
        perform(asAgent(post("/api/v1/ticket-settings/fields")).content("{}")).andExpect(status().isForbidden());

        perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Поле\",\"custom\":{\"" + code + "\":\"mac\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_custom_field"))
                .andExpect(jsonPath("$.field").value("custom." + code));
        perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Поле\",\"custom\":{\"unknown_" + suffix + "\":1}}"))
                .andExpect(status().isBadRequest());
        String id = read(body(perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Поле\",\"custom\":{\"" + code + "\":\"linux\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custom." + code).value("linux"))), "$.id");

        String found = body(perform(asAgent(get("/api/v1/tickets").param("custom", code + ":linux"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(found, "$.items[*].id")).containsExactly(id);
        patchAs(id, agent, "AGT", "{\"custom\":{\"" + code + "\":null}}")
                .andExpect(jsonPath("$.custom." + code).doesNotExist());
    }

    @Test
    void routingRuleAndRoundRobinAssign() throws Exception {
        String group = Ids.newId().toString();
        String a1 = Ids.newId().toString();
        String a2 = Ids.newId().toString();
        String keyword = "принтер" + suffix;
        perform(asAdmin(post("/api/v1/ticket-settings/routing-rules")).content("""
                {"name":"Печать","position":10,"conditions":{"keywords":["%s"],"ticketTypes":["incident"]},
                 "groupId":"%s"}""".formatted(keyword, group))).andExpect(status().isCreated());
        perform(as(put("/api/v1/ticket-settings/group-dispatch/" + group), admin, "ADM")
                .content("{\"strategy\":\"round_robin\",\"members\":[\"" + a1 + "\",\"" + a2 + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("round_robin"));

        String t1 = createIncident("Сломался " + keyword.toUpperCase());
        assertThat((String) read(t1, "$.groupId")).isEqualTo(group);
        assertThat((String) read(t1, "$.assigneeId")).isEqualTo(a1);
        assertThat((String) read(t1, "$.status.code")).isEqualTo("assigned");
        String t2 = createIncident("Опять " + keyword);
        assertThat((String) read(t2, "$.assigneeId")).isEqualTo(a2);
        String other = createIncident("Не про печать");
        assertThat((Object) read(other, "$.groupId")).isNull();

        String feed = body(perform(asAgent(get("/api/v1/tickets/" + read(t1, "$.id") + "/activity"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(feed, "$.items[*].meta.event")).contains("routed");
    }

    @Test
    void workflowCanBeChangedAndIsValidated() throws Exception {
        var res = perform(asAgent(get("/api/v1/ticket-settings/workflows/change")))
                .andExpect(status().isOk());
        String etag = res.andReturn().getResponse().getHeader("ETag");
        String wf = body(res);
        String review = "review_" + suffix;
        // Без перехода в финал из «review» — ошибка
        String broken = wf.replace("\"statuses\":[", "\"statuses\":[{\"code\":\"" + review + "\",\"name\":\"Ревью\",\"category\":\"in_progress\",\"initial\":false},");
        perform(asAdmin(put("/api/v1/ticket-settings/workflows/change")).header("If-Match", etag).content(broken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("field_required"));
        String fixed = broken.replace("\"transitions\":[", "\"transitions\":[{\"from\":\"" + review + "\",\"to\":\"closed\","
                + "\"name\":\"Принять\",\"roles\":[\"CHG\"],\"requiredFields\":[],\"trigger\":\"manual\"},");
        perform(asAdmin(put("/api/v1/ticket-settings/workflows/change")).header("If-Match", etag).content(fixed))
                .andExpect(status().isOk());
        perform(asAdmin(put("/api/v1/ticket-settings/workflows/change")).header("If-Match", etag).content(fixed))
                .andExpect(status().isPreconditionFailed());
        String saved = body(perform(asAgent(get("/api/v1/ticket-settings/workflows/change"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(saved, "$.statuses[*].code")).contains(review);
        // Возвращаем как было, чтобы повторный прогон начинался с того же процесса
        String current = perform(asAgent(get("/api/v1/ticket-settings/workflows/change"))).andReturn().getResponse()
                .getHeader("ETag");
        perform(asAdmin(put("/api/v1/ticket-settings/workflows/change")).header("If-Match", current).content(wf))
                .andExpect(status().isOk());
    }

    @Test
    void priorityMatrixHasDefaults() throws Exception {
        perform(asAgent(get("/api/v1/ticket-settings/priority-matrix")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.high.high").value("P1"))
                .andExpect(jsonPath("$.cells.low.low").value("P4"));
        perform(asAdmin(put("/api/v1/ticket-settings/priority-matrix")).header("If-Match", "\"0\"")
                .content("{\"cells\":{\"high\":{\"high\":\"P9\"}}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savedViewsAndTemplates() throws Exception {
        String view = body(perform(asAgent(post("/api/v1/ticket-views")).content("""
                {"name":"Мои срочные","visibility":"personal","filter":{"scope":"assigned_to_me","priority":"P1,P2"},
                 "sort":"due","columns":[{"field":"number","width":120}]}"""))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.filter.priority").value("P1,P2")));
        String viewId = read(view, "$.id");
        perform(asAgent(post("/api/v1/ticket-views")).content("{\"name\":\"Общее\",\"visibility\":\"shared\",\"filter\":{}}"))
                .andExpect(status().isForbidden());
        perform(as(get("/api/v1/ticket-views"), agent2, "AGT"))
                .andExpect(jsonPath("$[?(@.id == '" + viewId + "')]").isEmpty());
        perform(asAgent(patch("/api/v1/ticket-views/" + viewId)).header("If-Match", "\"0\"").content("{\"name\":\"Горит\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Горит"));

        String tpl = body(perform(asAgent(post("/api/v1/response-templates")).content("""
                {"name":"Перезагрузка %s","body":"Здравствуйте! Заявка {{ticket.number}}: перезагрузите ПК.",
                 "visibility":"personal"}""".formatted(suffix))).andExpect(status().isCreated()));
        perform(asAgent(get("/api/v1/response-templates").param("q", suffix)))
                .andExpect(jsonPath("$[0].id").value((String) read(tpl, "$.id")));
        perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/response-templates/" + read(tpl, "$.id")), agent2, "AGT"))
                .andExpect(status().isNotFound());
    }
}
