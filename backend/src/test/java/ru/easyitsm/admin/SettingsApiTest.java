package ru.easyitsm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Справочники, подразделения, дежурства и версии настроек (ADM-02, 03, 06). */
class SettingsApiTest extends AdminApiSupport {

    /** Справочник меняется в арендаторе OTHER: коды DEMO проверяют тесты рабочего места агента. */
    @Test
    void reasonCodesCanBeRenamedAndDisabledButNotRemoved() throws Exception {
        var res = mvc.perform(as(get("/api/v1/admin/dictionaries/resolution"), OTHER, "ADM"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String etag = res.getHeader("ETag");
        String body = res.getContentAsString();
        List<String> codes = JsonPath.read(body, "$.items[*].code");
        assertThat(codes).contains("fixed", "workaround");

        StringBuilder items = new StringBuilder();
        for (String c : codes) {
            String name = c.equals("fixed") ? "Исправлено " + suffix : JsonPath.<List<String>>read(body,
                    "$.items[?(@.code == '" + c + "')].name").getFirst();
            boolean active = !c.equals("no_repro");
            items.append(items.isEmpty() ? "" : ",").append("{\"code\":\"").append(c).append("\",\"name\":\"")
                    .append(name).append("\",\"active\":").append(active).append('}');
        }
        String withoutFixed = "{\"items\":[" + items.toString().replaceFirst("\\{\"code\":\"fixed\"[^}]*},?", "") + "]}";
        mvc.perform(as(put("/api/v1/admin/dictionaries/resolution"), OTHER, "ADM").header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(withoutFixed))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("code_removed"));
        mvc.perform(as(put("/api/v1/admin/dictionaries/resolution"), OTHER, "ADM").header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + ",{\"code\":\"Bad code\",\"name\":\"x\",\"active\":true}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code"));
        mvc.perform(as(put("/api/v1/admin/dictionaries/resolution"), OTHER, "ADM").header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isOk());

        // Рабочее место агента берёт активные коды из справочника.
        String agent = user(OTHER);
        String dir = mvc.perform(get("/api/v1/agent-workspace/directory").header("X-Dev-User", agent)
                        .header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "AGT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(dir, "$.resolutionCodes[*].name");
        List<String> ids = JsonPath.read(dir, "$.resolutionCodes[*].id");
        assertThat(names).contains("Исправлено " + suffix);
        assertThat(ids).doesNotContain("no_repro");

        String journal = mvc.perform(as(get("/api/v1/admin/config-versions").param("entity", "admin.dictionary")
                        .param("entityId", "resolution"), OTHER, "AUD"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(journal, "$.items[0].id");
        String diff = mvc.perform(as(get("/api/v1/admin/config-versions/" + versionId + "/diff"), OTHER, "AUD"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> paths = JsonPath.read(diff, "$.changes[*].path");
        assertThat(paths).contains("items[code=fixed].name");
    }

    @Test
    void orgUnitsTreeWithoutCyclesAndDuplicates() throws Exception {
        String org = mvc.perform(adm(post("/api/v1/admin/org-units")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"department\",\"name\":\"Финансы " + suffix + "\",\"code\":\"F" + suffix + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String parent = str(org, "$.id");
        String child = str(mvc.perform(adm(post("/api/v1/admin/org-units")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"department\",\"name\":\"Казначейство " + suffix + "\",\"parentId\":\"" + parent + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(adm(post("/api/v1/admin/org-units")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"department\",\"name\":\"финансы " + suffix + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("name_taken"));
        mvc.perform(adm(patch("/api/v1/admin/org-units/" + parent)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":\"" + child + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("parent_cycle"));
        mvc.perform(adm(patch("/api/v1/admin/org-units/" + child)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(as(get("/api/v1/admin/org-units/" + parent), OTHER, "ADM")).andExpect(status().isNotFound());
        mvc.perform(adm(get("/api/v1/admin/config-versions").param("entity", "admin.org_unit").param("entityId", child)))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void uiRecordsVersionsOfOtherModulesSettings() throws Exception {
        String id = "matrix-" + suffix;
        for (String cell : new String[] {"P2", "P1"}) {
            mvc.perform(adm(post("/api/v1/admin/config-versions")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"entity\":\"tickets.priority_matrix\",\"entityId\":\"" + id
                                    + "\",\"action\":\"update\",\"label\":\"Матрица\",\"snapshot\":{\"cells\":{\"high\":{\"high\":\""
                                    + cell + "\"}},\"version\":3}}"))
                    .andExpect(status().isCreated());
        }
        String list = mvc.perform(adm(get("/api/v1/admin/config-versions").param("entity", "tickets")
                        .param("entityId", id)))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].versionNo").value(2))
                .andExpect(jsonPath("$.items[0].source").value("ui"))
                .andReturn().getResponse().getContentAsString();
        String latest = JsonPath.read(list, "$.items[0].id");
        mvc.perform(adm(get("/api/v1/admin/config-versions/" + latest + "/diff")))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].path").value("cells.high.high"))
                .andExpect(jsonPath("$.changes[0].before").value("P2"))
                .andExpect(jsonPath("$.changes[0].after").value("P1"));
        mvc.perform(as(get("/api/v1/admin/config-versions/" + latest + "/diff"), OTHER, "ADM"))
                .andExpect(status().isNotFound());

        mvc.perform(adm(post("/api/v1/admin/config-versions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"admin.dictionary\",\"action\":\"update\",\"snapshot\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("server_managed"));
        mvc.perform(adm(post("/api/v1/admin/config-versions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"Bad Entity\",\"action\":\"update\",\"snapshot\":{}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_entity"));
    }

    @Test
    void dutyShiftsOnlyForGroupMembers() throws Exception {
        String member = user(DEMO);
        String stranger = user(DEMO);
        String group = group(DEMO, member);
        String shift = "{\"groupId\":\"" + group + "\",\"userId\":\"%s\",\"startsAt\":\"2030-03-01T06:00:00Z\","
                + "\"endsAt\":\"2030-03-01T18:00:00Z\",\"note\":\"Смена\"}";
        String created = mvc.perform(adm(post("/api/v1/admin/duty-shifts")).contentType(MediaType.APPLICATION_JSON)
                        .content(shift.formatted(member)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(adm(post("/api/v1/admin/duty-shifts")).contentType(MediaType.APPLICATION_JSON)
                        .content(shift.formatted(stranger)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(adm(post("/api/v1/admin/duty-shifts")).contentType(MediaType.APPLICATION_JSON)
                        .content(shift.formatted(member).replace("2030-03-01T18", "2030-02-01T18")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_window"));

        mvc.perform(as(get("/api/v1/admin/duty-shifts").param("groupId", group)
                        .param("from", "2030-03-01T00:00:00Z").param("to", "2030-03-02T00:00:00Z"), DEMO, "AGT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(member));
        mvc.perform(as(get("/api/v1/admin/duty-shifts").param("groupId", group)
                        .param("from", "2030-03-01T00:00:00Z").param("to", "2030-03-02T00:00:00Z"), OTHER, "AGT"))
                .andExpect(status().isNotFound());
        mvc.perform(adm(delete("/api/v1/admin/duty-shifts/" + str(created, "$.id")))).andExpect(status().isNoContent());
    }
}
