package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

/** Очередь агента: «взять следующую», эскалация, проверка личности, фильтры, идемпотентность. */
class TicketQueueTest extends TicketApiSupport {

    TicketQueueTest() {
        tenant = OTHER;
    }

    String inGroup(String title, String group) throws Exception {
        String id = read(createIncident(title), "$.id");
        patchAs(id, agent, "AGT", "{\"groupId\":\"" + group + "\"}").andExpect(status().isOk());
        return id;
    }

    @Test
    void takeNextPicksMostUrgentAndThenNothing() throws Exception {
        String group = Ids.newId().toString();
        String low = inGroup("Низкий", group);
        String high = inGroup("Высокий", group);
        patchAs(high, agent, "AGT", "{\"impact\":\"high\",\"urgency\":\"high\"}")
                .andExpect(jsonPath("$.priority").value("P1"));

        String body = "{\"groupId\":\"" + group + "\"}";
        perform(asAgent(post("/api/v1/tickets/take-next")).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(high))
                .andExpect(jsonPath("$.assigneeId").value(agent));
        perform(as(post("/api/v1/tickets/take-next"), agent2, "AGT").content(body))
                .andExpect(jsonPath("$.id").value(low));
        perform(asAgent(post("/api/v1/tickets/take-next")).content(body)).andExpect(status().isNoContent());
        perform(asReq(post("/api/v1/tickets/take-next")).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void escalationNeedsReasonAndMovesGroup() throws Exception {
        String group = Ids.newId().toString();
        String target = Ids.newId().toString();
        String id = inGroup("Эскалация", group);
        perform(asAgent(post("/api/v1/tickets/" + id + "/escalate"))
                .content("{\"kind\":\"functional\",\"groupId\":\"" + target + "\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        perform(asAgent(post("/api/v1/tickets/" + id + "/escalate")).header("If-Match", etag(id))
                .content("{\"kind\":\"functional\",\"groupId\":\"" + target + "\",\"reason\":\"Нужен сетевик\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(target));
        perform(asAgent(post("/api/v1/tickets/" + id + "/escalate")).header("If-Match", etag(id))
                .content("{\"kind\":\"hierarchical\",\"reason\":\"Горит\"}"))
                .andExpect(status().isOk());
        String feed = body(perform(asAgent(get("/api/v1/tickets/" + id + "/activity"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(feed, "$.items[*].meta.event")).contains("escalated");
    }

    @Test
    void identityCheckBlocksResolve() throws Exception {
        String category = read(body(perform(asAdmin(post("/api/v1/ticket-settings/categories"))
                .content("{\"name\":\"Сброс пароля " + System.nanoTime() + "\",\"ticketTypes\":[\"incident\"]}"))
                .andExpect(status().isCreated())), "$.id");
        var general = perform(asAgent(get("/api/v1/ticket-settings/general")));
        String etag = general.andReturn().getResponse().getHeader("ETag");
        perform(asAdmin(put("/api/v1/ticket-settings/general")).header("If-Match", etag)
                .content("{\"identityCheckCategoryIds\":[\"" + category + "\"]}"))
                .andExpect(status().isOk());

        String id = read(body(perform(asReq(post("/api/v1/tickets")).content(
                "{\"type\":\"incident\",\"title\":\"Сбросьте пароль\",\"categoryId\":\"" + category + "\"}"))
                .andExpect(jsonPath("$.identityCheck.required").value(true))), "$.id");
        perform(asAgent(post("/api/v1/tickets/" + id + "/take")).header("If-Match", etag(id))).andExpect(status().isOk());
        assertThat((String) read(fetch(id, agent, "AGT"), "$.status.category")).isEqualTo("in_progress");
        String resolve = "{\"to\":\"resolved\",\"resolutionCode\":\"fixed\",\"resolutionText\":\"Сброшен\"}";
        transition(id, agent, "AGT", resolve)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("identity_check_required"));
        perform(asAgent(post("/api/v1/tickets/" + id + "/identity-check")).content("{\"method\":\"callback\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.identityCheck.verifiedBy").value(agent));
        transition(id, agent, "AGT", resolve).andExpect(status().isOk());
    }

    @Test
    void listFiltersCountAndCursor() throws Exception {
        String group = Ids.newId().toString();
        String overdue = null;
        for (int i = 0; i < 3; i++) {
            overdue = inGroup("Очередь " + i, group);
        }
        String late = overdue;
        ru.easyitsm.platform.tenant.TenantContext.callAs(java.util.UUID.fromString(OTHER), () -> jdbc
                .sql("update ticket set sla_due_at = now() - interval '1 hour' where id = cast(:id as uuid)")
                .param("id", late).update());
        perform(asAgent(get("/api/v1/tickets").param("groupId", group).param("dueBefore", java.time.Instant.now().toString())))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(late));
        perform(asAgent(get("/api/v1/tickets/count").param("groupId", group)))
                .andExpect(jsonPath("$.count").value(3));
        String page1 = body(perform(asAgent(get("/api/v1/tickets").param("groupId", group)
                .param("sort", "due").param("limit", "2"))).andExpect(status().isOk()));
        assertThat(TicketLifecycleTest.JsonPathList.of(page1, "$.items[*].id")).hasSize(2);
        String cursor = read(page1, "$.nextCursor");
        String page2 = body(perform(asAgent(get("/api/v1/tickets").param("groupId", group)
                .param("sort", "due").param("limit", "2").param("cursor", cursor))));
        assertThat(TicketLifecycleTest.JsonPathList.of(page2, "$.items[*].id")).hasSize(1);
        perform(asAgent(get("/api/v1/tickets").param("groupId", group).param("assigneeId", "none")
                .param("awaitingAgent", "false"))).andExpect(jsonPath("$.items.length()").value(3));
        perform(asAgent(get("/api/v1/tickets").param("sort", "bogus"))).andExpect(status().isBadRequest());
    }

    @Test
    void createIsIdempotent() throws Exception {
        String key = Ids.newId().toString();
        String json = "{\"type\":\"incident\",\"title\":\"Дубль\"}";
        String first = body(perform(asReq(post("/api/v1/tickets")).header("Idempotency-Key", key).content(json))
                .andExpect(status().isCreated()));
        String second = body(perform(asReq(post("/api/v1/tickets")).header("Idempotency-Key", key).content(json)));
        assertThat((String) read(second, "$.number")).isEqualTo(read(first, "$.number"));
    }
}
