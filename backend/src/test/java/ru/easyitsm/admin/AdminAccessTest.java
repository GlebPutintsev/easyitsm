package ru.easyitsm.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Права «Управления» (раздел 3.2: «Настроить процессы и формы» — ADM; журнал — SEC и AUD). */
class AdminAccessTest extends AdminApiSupport {

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/setup")).andExpect(status().isUnauthorized());
    }

    @Test
    void requesterAndAgentCannotConfigure() throws Exception {
        for (String role : new String[] {"REQ", "AGT", "SEC", "AUD"}) {
            mvc.perform(as(get("/api/v1/admin/setup"), DEMO, role)).andExpect(status().isForbidden());
            mvc.perform(as(post("/api/v1/admin/imports"), DEMO, role).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"groups\",\"content\":\"name\\nX\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(as(put("/api/v1/admin/dictionaries/resolution"), DEMO, role).header("If-Match", "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                    .andExpect(status().isForbidden());
            mvc.perform(as(post("/api/v1/admin/org-units"), DEMO, role).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"department\",\"name\":\"X\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void staffReadsDictionariesRequesterDoesNot() throws Exception {
        mvc.perform(as(get("/api/v1/admin/dictionaries"), DEMO, "AGT")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/admin/org-units"), DEMO, "AGT")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/admin/dictionaries"), DEMO, "REQ")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/org-units"), DEMO, "REQ")).andExpect(status().isForbidden());
    }

    @Test
    void journalForAdminSecurityAndAuditor() throws Exception {
        for (String role : new String[] {"ADM", "SEC", "AUD"}) {
            mvc.perform(as(get("/api/v1/admin/config-versions"), DEMO, role)).andExpect(status().isOk());
        }
        mvc.perform(as(get("/api/v1/admin/config-versions"), DEMO, "AGT")).andExpect(status().isForbidden());
        String body = "{\"entity\":\"tickets.priority_matrix\",\"action\":\"update\",\"snapshot\":{}}";
        mvc.perform(as(post("/api/v1/admin/config-versions"), DEMO, "AUD").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/admin/config-versions"), DEMO, "AGT").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
    }

    @Test
    void dutyIsAssignedByAdminOnly() throws Exception {
        mvc.perform(as(post("/api/v1/admin/duty-shifts"), DEMO, "LEAD").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"01930000-0000-7000-8000-00000000aaaa",
                                 "userId":"01930000-0000-7000-8000-00000000bbbb",
                                 "startsAt":"2030-01-01T09:00:00Z","endsAt":"2030-01-01T18:00:00Z"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
