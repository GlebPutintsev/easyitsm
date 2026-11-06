package ru.easyitsm.mim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import ru.easyitsm.mim.api.MajorIncidentQueries;
import ru.easyitsm.mim.events.MajorIncidentClosed;
import ru.easyitsm.mim.events.MajorIncidentDeclared;
import ru.easyitsm.mim.events.OnCallPaged;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.problems.ProblemApiSupport;

/** Крупные инциденты (MIM-01…05): объявление, командный центр, оповещение, сообщения, закрытие с разбором. */
@RecordApplicationEvents
class MimApiTest extends ProblemApiSupport {

    @Autowired
    PagingJob pagingJob;

    @Autowired
    MajorIncidentQueries queries;

    @Autowired
    ApplicationEvents events;

    private void grant(String user, String role) {
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                insert into role_grant (id, tenant_id, role, user_id)
                values (:id, cast(:t as uuid), :r, cast(:u as uuid))""")
                .param("id", Ids.newId()).param("t", DEMO).param("r", role).param("u", user).update());
    }

    private String declare(String user, String roles, String ticketId) throws Exception {
        return body(perform(as(post("/api/v1/major-incidents"), user, roles)
                .content("{\"ticketId\":\"" + ticketId + "\",\"reason\":\"Не работает почта у всех\"}"))
                .andExpect(status().isCreated()));
    }

    private String etag(String id, String user, String roles) throws Exception {
        return perform(as(get("/api/v1/major-incidents/" + id), user, roles)).andReturn().getResponse()
                .getHeader("ETag");
    }

    @Test
    void leadDeclaresAndIncidentBecomesP1WithCommandCenter() throws Exception {
        grant(lead, "LEAD");
        String inc = incident(uniq("Почта недоступна"));
        String json = declare(lead, "AGT,LEAD", inc);
        String id = read(json, "$.id");
        assertThat((String) read(json, "$.state")).isEqualTo("active");
        assertThat((List<String>) read(json, "$.roles[?(@.role=='manager')].userId")).containsExactly(lead);
        assertThat((Boolean) read(json, "$.permissions.manage")).isTrue();
        assertThat((String) read(ticket(inc, agent, "AGT"), "$.priority")).isEqualTo("P1");
        assertThat(events.stream(MajorIncidentDeclared.class)).anyMatch(e -> e.mimId().toString().equals(id));

        // Агент группы видит командный центр, но не ведёт его; чужой группы — 404; другой арендатор — 404.
        perform(as(get("/api/v1/major-incidents/" + id), agent, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.manage").value(false));
        perform(as(get("/api/v1/major-incidents/" + id), outsider, "AGT")).andExpect(status().isNotFound());
        perform(as(get("/api/v1/major-incidents/" + id), lead, "MIM", OTHER)).andExpect(status().isNotFound());
        perform(as(get("/api/v1/major-incidents/by-ticket/" + inc), agent, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // Повторно тот же инцидент — 409; заявитель объявить не может.
        perform(as(post("/api/v1/major-incidents"), lead, "AGT,LEAD")
                .content("{\"ticketId\":\"" + inc + "\",\"reason\":\"ещё раз\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_major"));
        perform(as(post("/api/v1/major-incidents"), requester, "REQ")
                .content("{\"ticketId\":\"" + inc + "\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        // Менеджер — только MIM или LEAD.
        String other = incident(uniq("Другое"));
        perform(as(post("/api/v1/major-incidents"), lead, "AGT,LEAD")
                .content("{\"ticketId\":\"" + other + "\",\"reason\":\"x\",\"managerId\":\"" + agent + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_manager"));

        assertThat(TenantContext.callAs(UUID.fromString(DEMO), () -> queries.forTicket(UUID.fromString(inc))))
                .hasValueSatisfying(a -> assertThat(a.state()).isEqualTo("active"));
    }

    @Test
    void agentRequestNeedsApproval() throws Exception {
        grant(lead, "LEAD");
        String inc = incident(uniq("Падает портал"));
        String json = declare(agent, "AGT", inc);
        String id = read(json, "$.id");
        assertThat((String) read(json, "$.state")).isEqualTo("requested");
        assertThat((String) read(ticket(inc, agent, "AGT"), "$.priority")).isNotEqualTo("P1");

        perform(as(post("/api/v1/major-incidents/" + id + "/approve"), agent, "AGT"))
                .andExpect(status().isForbidden());
        perform(as(post("/api/v1/major-incidents/" + id + "/approve"), lead, "AGT,LEAD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.roles[0].userId").value(lead));
        assertThat((String) read(ticket(inc, agent, "AGT"), "$.priority")).isEqualTo("P1");
        perform(as(post("/api/v1/major-incidents/" + id + "/approve"), lead, "AGT,LEAD"))
                .andExpect(status().isConflict());

        String inc2 = incident(uniq("Ложная тревога"));
        String id2 = read(declare(agent, "AGT", inc2), "$.id");
        perform(as(post("/api/v1/major-incidents/" + id2 + "/reject"), lead, "AGT,LEAD")
                .content("{\"reason\":\"Затронут один пользователь\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("rejected"));
    }

    @Test
    void commandCenterRolesTasksChildrenAndUpdates() throws Exception {
        grant(lead, "LEAD");
        String inc = incident(uniq("Сеть офиса"));
        String child = incident(uniq("Нет интернета на 3 этаже"));
        String id = read(declare(lead, "AGT,LEAD", inc), "$.id");

        // Гипотеза с If-Match.
        perform(as(patch("/api/v1/major-incidents/" + id), lead, "AGT,LEAD").content("{\"hypothesis\":\"Коммутатор\"}"))
                .andExpect(status().isPreconditionRequired());
        perform(as(patch("/api/v1/major-incidents/" + id), lead, "AGT,LEAD")
                .header("If-Match", etag(id, lead, "AGT,LEAD")).content("{\"hypothesis\":\"Коммутатор\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hypothesis").value("Коммутатор"));

        // Роль и задача агенту: держатель роли ведёт командный центр, исполнитель закрывает свою задачу.
        perform(as(put("/api/v1/major-incidents/" + id + "/roles/tech_lead"), lead, "AGT,LEAD")
                .content("{\"userId\":\"" + agent + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_user"));
        grant(agent, "AGT");
        perform(as(put("/api/v1/major-incidents/" + id + "/roles/tech_lead"), lead, "AGT,LEAD")
                .content("{\"userId\":\"" + agent + "\"}")).andExpect(status().isOk());
        perform(as(get("/api/v1/major-incidents/" + id), agent, "AGT"))
                .andExpect(jsonPath("$.permissions.manage").value(true));
        String withTask = body(perform(as(post("/api/v1/major-incidents/" + id + "/tasks"), lead, "AGT,LEAD")
                .content("{\"title\":\"Перезагрузить коммутатор\",\"assigneeId\":\"" + prb + "\"}"))
                .andExpect(status().isOk()));
        String taskId = read(withTask, "$.tasks[0].id");
        perform(as(put("/api/v1/major-incidents/" + id + "/tasks/" + taskId + "/done"), prb, "PRB")
                .content("{\"done\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].done").value(true));

        // Дочерний инцидент и сообщение пользователям: публичная запись в обеих заявках, страница статуса.
        perform(as(post("/api/v1/major-incidents/" + id + "/children"), agent, "AGT")
                .content("{\"incidentIds\":[\"" + child + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.children[0].id").value(child));
        String templates = body(perform(as(get("/api/v1/mim/templates"), agent, "AGT")).andExpect(status().isOk()));
        String templateId = read(templates, "$[0].id");
        String rendered = body(perform(as(post("/api/v1/major-incidents/" + id + "/render"), agent, "AGT")
                .content("{\"templateId\":\"" + templateId + "\"}")).andExpect(status().isOk()));
        assertThat((String) read(rendered, "$.body")).doesNotContain("{{");
        String marker = uniq("Восстанавливаем сеть");
        String next = Instant.now().plus(Duration.ofMinutes(30)).toString();
        perform(as(post("/api/v1/major-incidents/" + id + "/updates"), agent, "AGT")
                .content("{\"audience\":\"users\",\"title\":\"" + marker + "\",\"body\":\"Работаем\","
                        + "\"statusPage\":true,\"nextUpdateAt\":\"" + next + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updates[0].title").value(marker));
        assertThat(body(perform(as(get("/api/v1/tickets/" + child + "/activity"), requester, "REQ"))))
                .contains(marker);
        String statusPage = body(perform(as(get("/api/v1/status"), requester, "REQ")).andExpect(status().isOk()));
        assertThat((List<String>) read(statusPage, "$.majorIncidents[*].lastUpdate.title")).contains(marker);

        perform(as(post("/api/v1/major-incidents/" + id + "/updates"), agent, "AGT")
                .content("{\"audience\":\"press\",\"title\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isBadRequest());
        // Заявитель настройки не читает и не правит.
        perform(as(post("/api/v1/mim/templates"), agent, "AGT")
                .content("{\"name\":\"x\",\"audience\":\"users\",\"title\":\"t\",\"body\":\"b\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pagingEscalatesWithoutAckAndStopsOnAck() throws Exception {
        grant(lead, "LEAD");
        String first = Ids.newId().toString();
        String firstGroup = newGroup(first);
        String inc = incident(uniq("Биллинг стоит"));
        String id = read(declare(lead, "AGT,LEAD", inc), "$.id");

        String chain = body(perform(as(post("/api/v1/mim/chains"), lead, "MIM")
                .content("{\"name\":\"" + uniq("Дежурные биллинга") + "\",\"steps\":[{\"groupId\":\"" + firstGroup
                        + "\",\"waitMinutes\":5},{\"userId\":\"" + prb + "\",\"waitMinutes\":5}]}"))
                .andExpect(status().isOk()));
        String chainId = read(chain, "$.id");

        String json = body(perform(as(post("/api/v1/major-incidents/" + id + "/pages"), lead, "AGT,LEAD")
                .content("{\"chainId\":\"" + chainId + "\"}")).andExpect(status().isOk()));
        String pageId = read(json, "$.pages[0].id");
        assertThat((String) read(json, "$.pages[0].state")).isEqualTo("paging");
        assertThat((List<String>) read(json, "$.pages[0].targets[*].userId")).containsExactly(first);
        assertThat(events.stream(OnCallPaged.class)).anyMatch(e -> e.userId().toString().equals(first));

        // Оповещённый видит командный центр, даже не видя заявку.
        perform(as(get("/api/v1/major-incidents/" + id), first, "AGT")).andExpect(status().isOk());

        // Время шага вышло: следующий шаг.
        pagingJob.runOnce(Instant.now().plus(Duration.ofMinutes(6)));
        json = body(perform(as(get("/api/v1/major-incidents/" + id), lead, "AGT,LEAD")));
        assertThat((Integer) read(json, "$.pages[0].step")).isEqualTo(1);
        assertThat((List<String>) read(json, "$.pages[0].targets[*].userId")).contains(first, prb);

        // Не оповещённый подтвердить не может; оповещённый — останавливает эскалацию.
        perform(as(post("/api/v1/major-incidents/pages/" + pageId + "/ack"), outsider, "AGT"))
                .andExpect(status().isNotFound());
        perform(as(post("/api/v1/major-incidents/pages/" + pageId + "/ack"), prb, "PRB"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pages[0].state").value("acknowledged"))
                .andExpect(jsonPath("$.pages[0].ackedBy").value(prb));
        pagingJob.runOnce(Instant.now().plus(Duration.ofMinutes(20)));
        perform(as(get("/api/v1/major-incidents/" + id), lead, "AGT,LEAD"))
                .andExpect(jsonPath("$.pages[0].state").value("acknowledged"));
    }

    @Test
    void closeAfterRestoreDraftsPirAndCreatesProblem() throws Exception {
        grant(lead, "LEAD");
        String inc = incident(uniq("1С недоступна"));
        String id = read(declare(lead, "AGT,LEAD", inc), "$.id");
        perform(as(post("/api/v1/major-incidents/" + id + "/events"), lead, "AGT,LEAD")
                .content("{\"text\":\"Откатили обновление\"}")).andExpect(status().isOk());

        perform(as(post("/api/v1/major-incidents/" + id + "/close"), lead, "AGT,LEAD")
                .content("{\"createProblem\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_restored"));
        perform(as(post("/api/v1/major-incidents/" + id + "/restore"), lead, "AGT,LEAD").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("restored"));
        // Агент группы без роли закрыть не может.
        perform(as(post("/api/v1/major-incidents/" + id + "/close"), agent, "AGT").content("{}"))
                .andExpect(status().isForbidden());

        String json = body(perform(as(post("/api/v1/major-incidents/" + id + "/close"), lead, "AGT,LEAD")
                .content("{\"createProblem\":true}")).andExpect(status().isOk()));
        assertThat((String) read(json, "$.state")).isEqualTo("closed");
        assertThat((String) read(json, "$.pir")).contains("Хронология").contains("Откатили обновление");
        assertThat((String) read(json, "$.problem.number")).startsWith("PRB-");
        assertThat(events.stream(MajorIncidentClosed.class)).anyMatch(e -> e.mimId().toString().equals(id));

        String problem = body(perform(as(get("/api/v1/problems/" + read(json, "$.problem.id")), lead, "AGT,LEAD")));
        assertThat((String) read(problem, "$.source")).isEqualTo("major_incident");
        assertThat((List<String>) read(problem, "$.incidents[*].id")).contains(inc);

        perform(as(put("/api/v1/major-incidents/" + id + "/pir"), lead, "AGT,LEAD")
                .header("If-Match", etag(id, lead, "AGT,LEAD")).content("{\"pir\":\"# Итог\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pir").value("# Итог"));
        String statusPage = body(perform(as(get("/api/v1/status"), requester, "REQ")));
        assertThat((List<String>) read(statusPage, "$.majorIncidents[*].id")).doesNotContain(id);
    }
}
