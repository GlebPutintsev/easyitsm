package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.events.TicketCreated;
import ru.easyitsm.tickets.events.TicketFirstResponded;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/** Создание, нумерация, приоритет и жизненный цикл инцидента 5.1 через REST. */
@RecordApplicationEvents
class TicketLifecycleTest extends TicketApiSupport {

    @Autowired
    ApplicationEvents events;

    @Test
    void requesterCreatesIncidentWithNumberPriorityAndEvent() throws Exception {
        String json = body(perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Не работает почта\",\"description\":\"С утра\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.number").value(org.hamcrest.Matchers.matchesPattern("INC-\\d{6}")))
                .andExpect(jsonPath("$.status.code").value("new"))
                .andExpect(jsonPath("$.status.category").value("new"))
                .andExpect(jsonPath("$.priority").value("P4"))
                .andExpect(jsonPath("$.channel").value("portal"))
                .andExpect(jsonPath("$.requesterId").value(requester))
                .andExpect(jsonPath("$.description").value("С утра"))
                .andExpect(jsonPath("$.permissions.internalNotes").value(false)));
        String id = read(json, "$.id");
        assertThat(events.stream(TicketCreated.class).filter(e -> e.ticketId().toString().equals(id))).hasSize(1);

        String second = createIncident("Ещё одна");
        int n1 = Integer.parseInt(((String) read(json, "$.number")).substring(4));
        int n2 = Integer.parseInt(((String) read(second, "$.number")).substring(4));
        assertThat(n2).isGreaterThan(n1);
    }

    @Test
    void priorityFollowsMatrixAndManualNeedsReason() throws Exception {
        perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Лежит 1С\",\"impact\":\"high\",\"urgency\":\"high\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("P1"))
                .andExpect(jsonPath("$.channel").value("agent"));
        perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Вручную\",\"priority\":\"P2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("field_required"))
                .andExpect(jsonPath("$.field").value("priorityReason"));
        String id = read(createIncident("Средний"), "$.id");
        patchAs(id, agent, "AGT", "{\"impact\":\"medium\",\"urgency\":\"high\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("P2"))
                .andExpect(header().string("ETag", "\"1\""));
        patchAs(id, agent, "AGT", "{\"priority\":\"P1\",\"priorityReason\":\"Директор\"}")
                .andExpect(jsonPath("$.priority").value("P1")).andExpect(jsonPath("$.priorityManual").value(true));
        String history = body(perform(asAgent(get("/api/v1/tickets/" + id + "/history"))));
        assertThat(JsonPathList.of(history, "$.items[?(@.field == 'priority')].reason")).contains("Директор");
    }

    @Test
    void fullIncidentLifecycle() throws Exception {
        String id = read(createIncident("Принтер не печатает"), "$.id");
        String group = newGroup(agent);

        // Новая → Назначена: группа задана, переход по триггеру group_assigned
        patchAs(id, agent, "AGT", "{\"groupId\":\"" + group + "\"}")
                .andExpect(jsonPath("$.status.code").value("assigned"));

        // Взять в работу: исполнитель = я, время реакции
        perform(asAgent(post("/api/v1/tickets/" + id + "/take")).header("If-Match", etag(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.code").value("in_progress"))
                .andExpect(jsonPath("$.assigneeId").value(agent))
                .andExpect(jsonPath("$.firstResponseAt").exists());
        assertThat(events.stream(TicketFirstResponded.class).filter(e -> e.ticketId().toString().equals(id))
                .map(TicketFirstResponded::via)).containsExactly("status");

        // Ожидание требует причину и срок возврата
        transition(id, agent, "AGT", "{\"to\":\"pending\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("reason_code"));
        String until = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        transition(id, agent, "AGT", "{\"to\":\"pending\",\"reasonCode\":\"waiting_user\",\"pendingUntil\":\"" + until + "\"}")
                .andExpect(jsonPath("$.status.category").value("pending"))
                .andExpect(jsonPath("$.pendingReason").value("waiting_user"));
        assertThat(events.stream(TicketStatusChanged.class)
                .filter(e -> e.ticketId().toString().equals(id) && e.ticket().statusCategory() == StatusCategory.PENDING)
                .map(TicketStatusChanged::reasonCode)).containsExactly("waiting_user");

        // Ответ заявителя возвращает в работу (триггер requester_reply)
        perform(asReq(post("/api/v1/tickets/" + id + "/activity")).content("{\"body\":\"Вот скриншот\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.visibility").value("public"));
        assertThat((String) read(fetch(id, agent, "AGT"), "$.status.code")).isEqualTo("in_progress");
        assertThat((Boolean) read(fetch(id, agent, "AGT"), "$.awaitingAgent")).isTrue();

        // Решение требует код и описание
        transition(id, agent, "AGT", "{\"to\":\"resolved\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("resolution"));
        transition(id, agent, "AGT", "{\"to\":\"resolved\",\"resolutionCode\":\"fixed\",\"resolutionText\":\"Заменили картридж\"}")
                .andExpect(jsonPath("$.status.code").value("resolved")).andExpect(jsonPath("$.resolvedAt").exists());

        // Заявитель возвращает с комментарием (INC-08): счётчик возвратов +1
        transition(id, requester, "REQ", "{\"to\":\"in_progress\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("comment"));
        transition(id, requester, "REQ", "{\"to\":\"in_progress\",\"comment\":\"Снова не печатает\"}")
                .andExpect(jsonPath("$.status.code").value("in_progress"))
                .andExpect(jsonPath("$.reopenCount").value(1))
                .andExpect(jsonPath("$.resolutionCode").doesNotExist());

        transition(id, agent, "AGT", "{\"to\":\"resolved\",\"resolutionCode\":\"fixed\",\"resolutionText\":\"Почистили\"}");
        // Заявитель подтверждает решение
        String details = fetch(id, requester, "REQ");
        assertThat(JsonPathList.of(details, "$.transitions[*].to")).containsExactlyInAnyOrder("in_progress", "closed");
        transition(id, requester, "REQ", "{\"to\":\"closed\"}")
                .andExpect(jsonPath("$.status.category").value("closed")).andExpect(jsonPath("$.closedAt").exists());

        // Закрытую нельзя менять и комментировать публично
        patchAs(id, agent, "AGT", "{\"title\":\"Поздно\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ticket_closed"));
        perform(asReq(post("/api/v1/tickets/" + id + "/activity")).content("{\"body\":\"Ещё вопрос\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ticket_closed"));
    }

    @Test
    void requesterSeesOnlyPublicFeed() throws Exception {
        String id = read(createIncident("Лента"), "$.id");
        perform(asAgent(post("/api/v1/tickets/" + id + "/activity"))
                .content("{\"body\":\"Внутренняя заметка\",\"visibility\":\"internal\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("note"));
        perform(asAgent(post("/api/v1/tickets/" + id + "/activity"))
                .content("{\"body\":\"Ответ заявителю\",\"visibility\":\"public\"}"))
                .andExpect(jsonPath("$.kind").value("reply"));
        String forRequester = body(perform(asReq(get("/api/v1/tickets/" + id + "/activity"))));
        assertThat(JsonPathList.of(forRequester, "$.items[*].body")).containsExactly("Ответ заявителю");
        String forAgent = body(perform(asAgent(get("/api/v1/tickets/" + id + "/activity"))));
        assertThat(JsonPathList.of(forAgent, "$.items[*].body")).contains("Внутренняя заметка", "Ответ заявителю");
        perform(asReq(post("/api/v1/tickets/" + id + "/activity"))
                .content("{\"body\":\"Хочу заметку\",\"visibility\":\"internal\"}"))
                .andExpect(status().isForbidden());
        perform(asReq(get("/api/v1/tickets/" + id + "/history"))).andExpect(status().isForbidden());
        // Первый публичный ответ агента — реакция
        assertThat((String) read(fetch(id, agent, "AGT"), "$.firstResponseAt")).isNotNull();
    }

    @Test
    void cancelNeedsCommentAndWorksForRequester() throws Exception {
        String id = read(createIncident("Отмена"), "$.id");
        transition(id, requester, "REQ", "{\"to\":\"cancelled\"}").andExpect(status().isBadRequest());
        transition(id, requester, "REQ", "{\"to\":\"cancelled\",\"comment\":\"Само заработало\"}")
                .andExpect(jsonPath("$.status.category").value("cancelled"))
                .andExpect(jsonPath("$.resolutionText").value("Само заработало"));
    }

    @Test
    void transitionWithoutRightsIsRejected() throws Exception {
        String id = read(createIncident("Права"), "$.id");
        transition(id, requester, "REQ", "{\"to\":\"in_progress\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("transition_not_allowed"));
        perform(asAgent(patch("/api/v1/tickets/" + id)).content("{\"title\":\"x\"}"))
                .andExpect(status().isPreconditionRequired());
        perform(asAgent(patch("/api/v1/tickets/" + id)).header("If-Match", "\"7\"").content("{\"title\":\"x\"}"))
                .andExpect(status().isPreconditionFailed());
        patchAs(id, requester, "REQ", "{\"title\":\"Хочу поменять\"}").andExpect(status().isForbidden());
    }

    @Test
    void ticketIsReadableByNumber() throws Exception {
        String json = createIncident("По номеру");
        String number = read(json, "$.number");
        perform(asReq(get("/api/v1/tickets/" + number))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) read(json, "$.id")));
    }

    /** Чтение списков из JsonPath с фильтрами. */
    static final class JsonPathList {
        static List<Object> of(String json, String path) {
            return com.jayway.jsonpath.JsonPath.read(json, path);
        }
    }
}
