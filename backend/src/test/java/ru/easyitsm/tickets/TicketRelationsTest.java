package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

/** Связи, подзадачи, наблюдатели и упоминания, слияние, массовые операции. */
class TicketRelationsTest extends TicketApiSupport {

    @Test
    void linksAreVisibleFromBothSidesAndCyclesRejected() throws Exception {
        String parent = read(createIncident("Массовый сбой VPN"), "$.id");
        String child = read(createIncident("Не подключается VPN"), "$.id");
        String childNumber = read(fetch(child, agent, "AGT"), "$.number");
        perform(asAgent(post("/api/v1/tickets/" + parent + "/links"))
                .content("{\"targetId\":\"" + childNumber + "\",\"kind\":\"parent_of\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("parent_of"))
                .andExpect(jsonPath("$.ticket.number").value(childNumber));
        String fromChild = body(perform(asAgent(get("/api/v1/tickets/" + child + "/links"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(fromChild, "$[*].kind")).containsExactly("child_of");

        perform(asAgent(post("/api/v1/tickets/" + parent + "/links"))
                .content("{\"targetId\":\"" + child + "\",\"kind\":\"parent_of\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("link_exists"));
        perform(asAgent(post("/api/v1/tickets/" + child + "/links"))
                .content("{\"targetId\":\"" + parent + "\",\"kind\":\"parent_of\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("link_cycle"));
        perform(asReq(post("/api/v1/tickets/" + parent + "/links"))
                .content("{\"targetId\":\"" + child + "\",\"kind\":\"relates_to\"}"))
                .andExpect(status().isForbidden());

        String linkId = read(body(perform(asAgent(get("/api/v1/tickets/" + parent + "/links")))), "$[0].id");
        perform(asAgent(delete("/api/v1/tickets/" + child + "/links/" + linkId))).andExpect(status().isNoContent());
        assertThat(TicketLifecycleTest.JsonPathList.of(body(perform(asAgent(get("/api/v1/tickets/" + parent + "/links")))),
                "$")).isEmpty();
    }

    @Test
    void subtasksBlockResolution() throws Exception {
        String id = read(createIncident("Подключить рабочее место"), "$.id");
        perform(asAgent(post("/api/v1/tickets/" + id + "/take")).header("If-Match", etag(id)));
        String due = java.time.Instant.now().plusSeconds(86_400).toString();
        String sub = body(perform(asAgent(post("/api/v1/tickets/" + id + "/subtasks"))
                .content("{\"title\":\"Выдать ноутбук\",\"assigneeId\":\"" + agent2 + "\",\"dueAt\":\"" + due + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("task"))
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.startsWith("TSK-")))
                .andExpect(jsonPath("$.parentId").value(id))
                .andExpect(jsonPath("$.assigneeId").value(agent2)));
        String subId = read(sub, "$.id");
        assertThat(TicketLifecycleTest.JsonPathList.of(body(perform(asAgent(get("/api/v1/tickets/" + id + "/subtasks")))),
                "$[*].id")).containsExactly(subId);

        transition(id, agent, "AGT", "{\"to\":\"resolved\",\"resolutionCode\":\"done\",\"resolutionText\":\"Готово\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("open_subtasks"));
        transition(subId, agent2, "AGT", "{\"to\":\"done\"}").andExpect(jsonPath("$.status.category").value("closed"));
        transition(id, agent, "AGT", "{\"to\":\"resolved\",\"resolutionCode\":\"done\",\"resolutionText\":\"Готово\"}")
                .andExpect(status().isOk());
    }

    @Test
    void mentionAddsWatcher() throws Exception {
        String id = read(createIncident("Упоминание"), "$.id");
        perform(asAgent(post("/api/v1/tickets/" + id + "/activity"))
                .content("{\"body\":\"Посмотри, пожалуйста\",\"visibility\":\"internal\",\"mentions\":[\"" + agent2 + "\"]}"))
                .andExpect(status().isCreated());
        String watchers = body(perform(asAgent(get("/api/v1/tickets/" + id + "/watchers"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(watchers, "$[*].userId")).containsExactly(agent2);
        assertThat(TicketLifecycleTest.JsonPathList.of(watchers, "$[*].reason")).containsExactly("mention");
        perform(as(delete("/api/v1/tickets/" + id + "/watchers/me"), agent2, "AGT")).andExpect(status().isNoContent());
    }

    @Test
    void mergeClosesDuplicatesAndMovesRequesters() throws Exception {
        String target = read(createIncident("Не работает Wi-Fi на 3 этаже"), "$.id");
        String otherRequester = Ids.newId().toString();
        String dup = read(body(perform(as(post("/api/v1/tickets"), otherRequester, "REQ")
                .content("{\"type\":\"incident\",\"title\":\"Wi-Fi пропал\"}"))), "$.id");
        queue(dup);

        String preview = body(perform(asAgent(post("/api/v1/tickets/" + target + "/merge"))
                .content("{\"sourceIds\":[\"" + dup + "\",\"" + target + "\"],\"preview\":true}"))
                .andExpect(status().isOk()));
        assertThat(TicketLifecycleTest.JsonPathList.of(preview, "$.sources[*].code")).containsExactly(null, "same_ticket");
        assertThat(TicketLifecycleTest.JsonPathList.of(preview, "$.watchersAdded")).containsExactly(otherRequester);
        assertThat((String) read(fetch(dup, agent, "AGT"), "$.status.category")).isEqualTo("new");

        perform(asAgent(post("/api/v1/tickets/" + target + "/merge"))
                .content("{\"sourceIds\":[\"" + dup + "\",\"" + target + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("merge_invalid"));
        perform(asAgent(post("/api/v1/tickets/" + target + "/merge")).content("{\"sourceIds\":[\"" + dup + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preview").value(false));

        String closed = fetch(dup, agent, "AGT");
        assertThat((String) read(closed, "$.status.category")).isEqualTo("closed");
        assertThat((String) read(closed, "$.resolutionCode")).isEqualTo("duplicate");
        String links = body(perform(asAgent(get("/api/v1/tickets/" + dup + "/links"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(links, "$[*].kind")).containsExactly("duplicate_of");
        // Заявитель дубликата теперь наблюдает основную заявку и видит её
        perform(as(get("/api/v1/tickets/" + target), otherRequester, "REQ")).andExpect(status().isOk());
    }

    @Test
    void bulkPreviewExecuteAndUndo() throws Exception {
        String a = read(createIncident("Массово 1"), "$.id");
        String b = read(createIncident("Массово 2"), "$.id");
        String group = newGroup(agent);
        String req = "{\"ticketIds\":[\"" + a + "\",\"" + b + "\"],\"action\":\"assign\",\"params\":{\"groupId\":\""
                + group + "\"}";

        String preview = body(perform(asAgent(post("/api/v1/tickets/bulk")).content(req + ",\"preview\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").doesNotExist()));
        assertThat(TicketLifecycleTest.JsonPathList.of(preview, "$.items[*].ok")).containsExactly(true, true);
        assertThat(TicketLifecycleTest.JsonPathList.of(preview, "$.items[0].changes[*].field")).contains("groupId", "status");
        assertThat((String) read(fetch(a, agent, "AGT"), "$.status.code")).isEqualTo("new");

        String done = body(perform(asAgent(post("/api/v1/tickets/bulk")).content(req + "}"))
                .andExpect(jsonPath("$.operationId").exists()).andExpect(jsonPath("$.undoUntil").exists()));
        assertThat((String) read(fetch(a, agent, "AGT"), "$.groupId")).isEqualTo(group);
        assertThat((String) read(fetch(b, agent, "AGT"), "$.status.code")).isEqualTo("assigned");

        String op = read(done, "$.operationId");
        perform(as(post("/api/v1/tickets/bulk/" + op + "/undo"), agent2, "AGT")).andExpect(status().isNotFound());
        perform(asAgent(post("/api/v1/tickets/bulk/" + op + "/undo"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].ok").value(org.hamcrest.Matchers.contains(true, true)));
        String restored = fetch(a, agent, "AGT");
        assertThat((String) read(restored, "$.groupId")).isEqualTo(supportGroup);
        assertThat((String) read(restored, "$.status.code")).isEqualTo("new");
        perform(asAgent(post("/api/v1/tickets/bulk/" + op + "/undo"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("undo_expired"));
    }

    @Test
    void bulkReportsPerTicketErrors() throws Exception {
        String a = read(createIncident("Массово решить"), "$.id");
        String result = body(perform(asAgent(post("/api/v1/tickets/bulk"))
                .content("{\"ticketIds\":[\"" + a + "\",\"" + Ids.newId() + "\"],\"action\":\"transition\","
                        + "\"params\":{\"to\":\"resolved\"}}")));
        List<Object> codes = TicketLifecycleTest.JsonPathList.of(result, "$.items[*].code");
        assertThat(codes).containsExactly("transition_not_allowed", "not_found");
    }
}
