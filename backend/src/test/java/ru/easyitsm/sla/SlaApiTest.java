package ru.easyitsm.sla;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;

/** Права, изоляция арендаторов, ETag и проверка на примере для каждого эндпоинта SLA (NFR-16). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlaApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";
    static final String USER = Ids.newId().toString();
    static final String SCHEDULE = """
            {"name":"Офис МСК","timezone":"Europe/Moscow",
             "week":{"MONDAY":["09:00-18:00"],"TUESDAY":["09:00-18:00"],"WEDNESDAY":["09:00-18:00"],
                     "THURSDAY":["09:00-18:00"],"FRIDAY":["09:00-18:00"]}}""";

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/sla/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sla/instances").param("ticketId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requesterAndAgentCannotConfigure() throws Exception {
        for (String role : new String[] {"REQ", "AGT"}) {
            mvc.perform(as(get("/api/v1/sla/policies"), DEMO, role)).andExpect(status().isForbidden());
            mvc.perform(as(get("/api/v1/sla/schedules"), DEMO, role)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, role).contentType(MediaType.APPLICATION_JSON)
                    .content(SCHEDULE)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/v1/sla/setup/defaults"), DEMO, role)).andExpect(status().isForbidden());
        }
        mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, "LEAD").contentType(MediaType.APPLICATION_JSON)
                .content(SCHEDULE)).andExpect(status().isForbidden());
    }

    @Test
    void requesterCannotReadInstancesButAgentCan() throws Exception {
        String ticket = UUID.randomUUID().toString();
        mvc.perform(as(get("/api/v1/sla/instances").param("ticketId", ticket), DEMO, "REQ"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/sla/instances").param("ticketId", ticket), DEMO, "AGT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/v1/sla/instances/" + UUID.randomUUID()), DEMO, "AGT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void scheduleLifecycleWithEtagHolidaysAndIsolation() throws Exception {
        String created = mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.week.SATURDAY.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(as(get("/api/v1/sla/schedules/" + id), DEMO, "ANL")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/sla/schedules/" + id), OTHER, "ADM")).andExpect(status().isNotFound());
        mvc.perform(as(patch("/api/v1/sla/schedules/" + id), OTHER, "ADM").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());

        String patchBody = "{\"week\":{\"MONDAY\":[\"09:00-13:00\",\"14:00-18:00\"]}}";
        mvc.perform(as(patch("/api/v1/sla/schedules/" + id), DEMO, "ADM")
                .contentType(MediaType.APPLICATION_JSON).content(patchBody)).andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch("/api/v1/sla/schedules/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.week.MONDAY[1]").value("14:00-18:00"));

        String holidays = """
                [{"date":"2026-11-03","name":"Предпраздничный","hours":["09:00-17:00"]},
                 {"date":"2026-11-04","name":"День народного единства"}]""";
        mvc.perform(as(put("/api/v1/sla/schedules/" + id + "/holidays").param("year", "2026"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(holidays))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].hours.length()").value(0));
        mvc.perform(as(get("/api/v1/sla/schedules/" + id + "/holidays").param("year", "2026"), OTHER, "ADM"))
                .andExpect(status().isNotFound());
        mvc.perform(as(put("/api/v1/sla/schedules/" + id + "/holidays").param("year", "2027"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(holidays))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("date_out_of_year"));
    }

    @Test
    void invalidScheduleIsProblemJson() throws Exception {
        mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, "ADM").contentType(MediaType.APPLICATION_JSON)
                        .content(SCHEDULE.replace("\"09:00-18:00\"]}}", "\"18:00-09:00\"]}}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_hours"))
                .andExpect(jsonPath("$.trace_id").exists());
        mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, "ADM").contentType(MediaType.APPLICATION_JSON)
                        .content(SCHEDULE.replace("Europe/Moscow", "Mars/Olympus")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_timezone"));
    }

    @Test
    void policyLifecyclePreviewAndIsolation() throws Exception {
        String schedule = JsonPath.read(mvc.perform(as(post("/api/v1/sla/schedules"), DEMO, "ADM")
                .contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String body = """
                {"name":"Инциденты бухгалтерии","kind":"sla","rank":50,"scheduleId":"%s",
                 "conditions":{"ticketTypes":["incident"],"serviceIds":["%s"]},
                 "targets":[
                   {"metric":"response","priority":"P1","amount":15,"unit":"minutes","calendar":"24x7"},
                   {"metric":"resolution","priority":"P3","amount":2,"unit":"business_days",
                    "breachActions":["notify:group_lead","escalate:hierarchical"]}]}"""
                .formatted(schedule, UUID.randomUUID());
        String created = mvc.perform(as(post("/api/v1/sla/policies"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pauseStatuses[0]").value("pending"))
                .andExpect(jsonPath("$.targets[1].thresholds.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(as(get("/api/v1/sla/policies/" + id), OTHER, "ADM")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/sla/policies/" + id), DEMO, "AUD")).andExpect(status().isOk());

        // «Проверить на примере»: P3 из чт 16:00 МСК, 2 рабочих дня = 18 ч → пн 16:00 МСК.
        mvc.perform(as(post("/api/v1/sla/policies/" + id + "/preview"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"P3\",\"startAt\":\"2026-11-05T13:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets[0].dueAt").value("2026-11-09T13:00:00Z"))
                .andExpect(jsonPath("$.targets[0].thresholds['50']").value("2026-11-06T13:00:00Z"));
        mvc.perform(as(post("/api/v1/sla/policies/" + id + "/preview"), OTHER, "ADM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"P3\",\"startAt\":\"2026-11-05T13:00:00Z\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(as(patch("/api/v1/sla/policies/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.targets.length()").value(2));
        mvc.perform(as(patch("/api/v1/sla/policies/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void invalidPolicyIsRejected() throws Exception {
        mvc.perform(as(post("/api/v1/sla/policies"), DEMO, "ADM").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"x","kind":"sla","targets":[
                          {"metric":"resolution","priority":"P1","amount":4,"unit":"hours","breachActions":["rm -rf"]}]}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/sla/policies"), DEMO, "ADM").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"x","kind":"sla","targets":[
                          {"metric":"resolution","priority":"P1","amount":4,"unit":"hours"},
                          {"metric":"resolution","priority":"P1","amount":8,"unit":"hours"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("duplicate_target"));
        mvc.perform(as(post("/api/v1/sla/policies"), DEMO, "ADM").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"x","kind":"sla","scheduleId":"%s","targets":[
                          {"metric":"resolution","priority":"P1","amount":4,"unit":"hours"}]}""".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void defaultsAreInstalledFromSection53() throws Exception {
        mvc.perform(as(post("/api/v1/sla/setup/defaults"), OTHER, "ADM").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Asia/Yekaterinburg\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targets.length()").value(8))
                .andExpect(jsonPath("$.targets[0].priority").value("P1"))
                .andExpect(jsonPath("$.targets[0].calendar").value("24x7"));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String roles) {
        return req.header("X-Dev-User", USER).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
    }
}
