package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;

/** Права и изоляция (ACC-01, ACC-02, ACC-04, AT-01): чужое и недоступное — 404, действия — по ролям. */
class TicketAccessTest extends TicketApiSupport {

    final String stranger = Ids.newId().toString();

    @Test
    void anonymousIsRejected() throws Exception {
        perform(get("/api/v1/tickets")).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantGets404() throws Exception {
        String id = read(createIncident("Изоляция"), "$.id");
        tenant = OTHER;
        perform(as(get("/api/v1/tickets/" + id), agent, "AGT,MIM"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        String list = body(perform(as(get("/api/v1/tickets"), agent, "MIM")));
        assertThat(TicketLifecycleTest.JsonPathList.of(list, "$.items[*].id")).doesNotContain(id);
    }

    @Test
    void otherRequesterGets404AndDoesNotSeeInList() throws Exception {
        String id = read(createIncident("Чужая"), "$.id");
        perform(as(get("/api/v1/tickets/" + id), stranger, "REQ")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id + "/activity"), stranger, "REQ")).andExpect(status().isNotFound());
        String list = body(perform(as(get("/api/v1/tickets").param("scope", "mine"), stranger, "REQ")));
        assertThat(TicketLifecycleTest.JsonPathList.of(list, "$.items")).isEmpty();
        String mine = body(perform(asReq(get("/api/v1/tickets").param("scope", "mine"))));
        assertThat(TicketLifecycleTest.JsonPathList.of(mine, "$.items[*].id")).contains(id);
    }

    @Test
    void watcherSeesTicket() throws Exception {
        String id = read(createIncident("Наблюдатель"), "$.id");
        perform(asAgent(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tickets/" + id + "/watchers/" + stranger))).andExpect(status().isNoContent());
        perform(as(get("/api/v1/tickets/" + id), stranger, "REQ")).andExpect(status().isOk());
        // Заявитель не может подписывать других
        perform(asReq(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tickets/" + id + "/watchers/" + agent2))).andExpect(status().isForbidden());
        perform(asReq(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tickets/" + id + "/watchers/me"))).andExpect(status().isNoContent());
    }

    @Test
    void requesterCannotSetSupportFields() throws Exception {
        perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Хитрый\",\"groupId\":\"" + Ids.newId() + "\"}"))
                .andExpect(status().isForbidden());
        perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"От чужого имени\",\"requesterId\":\"" + stranger + "\"}"))
                .andExpect(status().isForbidden());
        perform(asAgent(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"За сотрудника\",\"requesterId\":\"" + stranger + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requesterId").value(stranger));
    }

    @Test
    void auditorReadsButCannotWrite() throws Exception {
        String id = read(createIncident("Аудит"), "$.id");
        perform(as(post("/api/v1/tickets"), agent2, "AUD").content("{\"type\":\"incident\",\"title\":\"Нет\"}"))
                .andExpect(status().isForbidden());
        perform(as(get("/api/v1/tickets/" + id), agent2, "AUD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.edit").value(false));
        perform(as(get("/api/v1/tickets/" + id + "/history"), agent2, "AUD")).andExpect(status().isOk());
        perform(as(post("/api/v1/tickets/" + id + "/activity"), agent2, "AUD").content("{\"body\":\"Привет\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confidentialTicketIsVisibleOnlyToParticipants() throws Exception {
        String id = read(createIncident("Увольнение сотрудника"), "$.id");
        patchAs(id, agent, "AGT", "{\"confidential\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("confidentialReason"));
        // Агент назначает себя и закрывает доступ: остаётся участником
        patchAs(id, agent, "AGT", "{\"assigneeId\":\"" + agent + "\"}").andExpect(status().isOk());
        patchAs(id, agent, "AGT", "{\"confidential\":true,\"confidentialReason\":\"Кадровый вопрос\"}")
                .andExpect(jsonPath("$.confidential").value(true));
        perform(as(get("/api/v1/tickets/" + id), agent2, "AGT")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id), agent2, "MIM")).andExpect(status().isNotFound());
        perform(asReq(get("/api/v1/tickets/" + id))).andExpect(status().isOk());
        perform(asAgent(get("/api/v1/tickets/" + id))).andExpect(status().isOk());
        String list = body(perform(as(get("/api/v1/tickets").param("q", "Увольнение"), agent2, "AGT")));
        assertThat(TicketLifecycleTest.JsonPathList.of(list, "$.items[*].id")).doesNotContain(id);
    }

    @Test
    void agentSeesOnlyTicketsOfOwnGroups() throws Exception {
        String id = read(createIncident("Очередь своей группы"), "$.id");
        String outsider = Ids.newId().toString();
        perform(asAgent(get("/api/v1/tickets/" + id))).andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), agent2, "AGT")).andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT,CHG,KNW")).andExpect(status().isNotFound());
        String list = body(perform(as(get("/api/v1/tickets").param("q", "Очередь своей группы"), outsider, "AGT")));
        assertThat(TicketLifecycleTest.JsonPathList.of(list, "$.items[*].id")).doesNotContain(id);
        perform(as(get("/api/v1/tickets/count").param("groupId", supportGroup), outsider, "AGT"))
                .andExpect(jsonPath("$.count").value(0));
        // Менеджер крупных инцидентов видит все неконфиденциальные
        perform(as(get("/api/v1/tickets/" + id), outsider, "MIM")).andExpect(status().isOk());
        // Членство в группе без роли поддержки ничего не даёт
        String member = Ids.newId().toString();
        String group = newGroup(member);
        patchAs(id, agent, "AGT", "{\"groupId\":\"" + group + "\"}").andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), member, "REQ")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id), member, "AGT")).andExpect(status().isOk());
        // Прежняя группа заявку больше не видит
        perform(asAgent(get("/api/v1/tickets/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void unroutedTicketIsVisibleToEveryAgent() throws Exception {
        String id = read(body(perform(asReq(post("/api/v1/tickets"))
                .content("{\"type\":\"incident\",\"title\":\"Без группы\"}"))), "$.id");
        String outsider = Ids.newId().toString();
        perform(as(get("/api/v1/tickets/" + id), Ids.newId().toString(), "REQ")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id), outsider, "LEAD")).andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), Ids.newId().toString(), "KNW")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.assign").value(true));
        // Любой агент распределяет: назначает группу, дальше заявка — её
        patchAs(id, outsider, "AGT", "{\"groupId\":\"" + supportGroup + "\"}").andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT")).andExpect(status().isNotFound());
        perform(asAgent(get("/api/v1/tickets/" + id))).andExpect(status().isOk());
    }

    @Test
    void agentSeesTicketRegisteredForSomeoneElse() throws Exception {
        String outsider = Ids.newId().toString();
        String id = read(body(perform(as(post("/api/v1/tickets"), outsider, "AGT")
                .content("{\"type\":\"incident\",\"title\":\"По телефону\",\"requesterId\":\"" + stranger + "\"}"))
                .andExpect(status().isCreated())), "$.id");
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT")).andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), stranger, "REQ")).andExpect(status().isOk());
    }

    @Test
    void watcherFromOtherGroupReadsAndCommentsButCannotWork() throws Exception {
        String id = read(createIncident("Посмотри со стороны"), "$.id");
        String outsider = Ids.newId().toString();
        perform(asAgent(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tickets/" + id + "/watchers/" + outsider))).andExpect(status().isNoContent());
        perform(as(get("/api/v1/tickets/" + id), outsider, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.edit").value(false))
                .andExpect(jsonPath("$.permissions.comment").value(true))
                .andExpect(jsonPath("$.transitions").isEmpty());
        patchAs(id, outsider, "AGT", "{\"title\":\"Моё\"}").andExpect(status().isForbidden());
        perform(as(post("/api/v1/tickets/" + id + "/take"), outsider, "AGT").header("If-Match", etagAs(id, outsider, "AGT")))
                .andExpect(status().isForbidden());
        perform(as(post("/api/v1/tickets/" + id + "/activity"), outsider, "AGT")
                .content("{\"body\":\"Похоже на сеть\",\"visibility\":\"internal\"}")).andExpect(status().isCreated());
        // Сделать заявку своей через подписку других нельзя
        perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tickets/" + id + "/watchers/" + stranger), outsider, "AGT")).andExpect(status().isForbidden());
    }

    @Test
    void assigneeMustBelongToTicketGroup() throws Exception {
        String id = read(createIncident("Назначение"), "$.id");
        String outsider = Ids.newId().toString();
        patchAs(id, agent, "AGT", "{\"assigneeId\":\"" + outsider + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_assignment"))
                .andExpect(jsonPath("$.field").value("assigneeId"));
        patchAs(id, agent, "AGT", "{\"groupId\":\"" + Ids.newId() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("groupId"));
        patchAs(id, agent, "AGT", "{\"assigneeId\":\"" + agent2 + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeId").value(agent2));
        String other = newGroup(outsider);
        patchAs(id, agent, "AGT", "{\"groupId\":\"" + other + "\",\"assigneeId\":\"" + outsider + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assigneeId").value(outsider));
    }

    @Test
    void securityGroupMemberSeesConfidentialTicket() throws Exception {
        String id = read(createIncident("Расследование"), "$.id");
        String officer = Ids.newId().toString();
        String security = newGroup(officer);
        patchAs(id, agent, "AGT", "{\"assigneeId\":\"" + agent + "\"}").andExpect(status().isOk());
        patchAs(id, agent, "AGT", "{\"confidential\":true,\"confidentialReason\":\"ИБ\",\"securityGroupId\":\""
                + security + "\"}").andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), officer, "AGT")).andExpect(status().isOk());
        perform(as(get("/api/v1/tickets/" + id), agent2, "AGT")).andExpect(status().isNotFound());
        String list = body(perform(as(get("/api/v1/tickets").param("q", "Расследование"), officer, "AGT")));
        assertThat(TicketLifecycleTest.JsonPathList.of(list, "$.items[*].id")).contains(id);
    }

    @Test
    void settingsAreAdminOnly() throws Exception {
        perform(asAgent(get("/api/v1/ticket-settings/workflows/incident"))).andExpect(status().isOk());
        perform(asReq(get("/api/v1/ticket-settings/workflows/incident"))).andExpect(status().isForbidden());
        perform(asAgent(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/ticket-settings/priority-matrix")).header("If-Match", "\"0\"").content("{}"))
                .andExpect(status().isForbidden());
        perform(asReq(get("/api/v1/ticket-views"))).andExpect(status().isForbidden());
    }
}
