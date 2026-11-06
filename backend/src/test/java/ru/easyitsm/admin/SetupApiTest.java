package ru.easyitsm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.easyitsm.platform.tenant.TenantContext;

/** Мастер первичной настройки (ADM-01). */
class SetupApiTest extends AdminApiSupport {

    @Test
    void organizationStepWithEtagAndHistory() throws Exception {
        var res = mvc.perform(adm(get("/api/v1/admin/setup"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.organization").exists()).andReturn().getResponse();
        String etag = res.getHeader("ETag");
        String org = "{\"name\":\"ООО Ромашка " + suffix + "\",\"timezone\":\"Asia/Yekaterinburg\",\"locale\":\"ru\"}";

        mvc.perform(adm(put("/api/v1/admin/setup/organization")).contentType(MediaType.APPLICATION_JSON).content(org))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(adm(put("/api/v1/admin/setup/organization")).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(org.replace("Asia/Yekaterinburg", "Mars/Base")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_timezone"));
        mvc.perform(adm(put("/api/v1/admin/setup/organization")).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(org))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.timezone").value("Asia/Yekaterinburg"))
                .andExpect(jsonPath("$.steps.organization").value("done"));
        mvc.perform(adm(put("/api/v1/admin/setup/organization")).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(org))
                .andExpect(status().isPreconditionFailed());

        mvc.perform(adm(put("/api/v1/admin/setup/steps/mail")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"skipped\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps.mail").value("skipped"));
        mvc.perform(adm(put("/api/v1/admin/setup/steps/unknown")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"done\"}")).andExpect(status().isNotFound());
        mvc.perform(adm(post("/api/v1/admin/setup/complete"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").exists());

        String journal = mvc.perform(adm(get("/api/v1/admin/config-versions").param("entity", "admin.setup")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> labels = JsonPath.read(journal, "$.items[*].label");
        assertThat(labels).contains("Организация", "Мастер настройки");
    }

    @Test
    void checksReportWithoutConfiguration() throws Exception {
        // Сервер отправки может сохранить тест модуля почты в той же базе: проверяем форму ответа.
        mvc.perform(adm(post("/api/v1/admin/setup/checks/mail"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").isBoolean())
                .andExpect(jsonPath("$.results[0].code").isString());
        mvc.perform(adm(post("/api/v1/admin/setup/checks/sso"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").isBoolean());
    }

    /** Демоданные грузятся в арендатора OTHER, чтобы не менять очереди других тестов. */
    @Test
    void demoDataLoadsOnce() throws Exception {
        TenantContext.runAs(UUID.fromString(OTHER), () -> jdbc.sql("delete from setup_state").update());
        String body = mvc.perform(as(post("/api/v1/admin/setup/demo-data"), OTHER, "ADM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(5))
                .andExpect(jsonPath("$.slaInstalled").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.tickets[0]")).startsWith("INC-");
        mvc.perform(as(post("/api/v1/admin/setup/demo-data"), OTHER, "ADM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("demo_already_loaded"));
        mvc.perform(as(get("/api/v1/admin/setup"), OTHER, "ADM"))
                .andExpect(jsonPath("$.steps.demo").value("done")).andExpect(jsonPath("$.steps.sla").value("done"));
        mvc.perform(as(get("/api/v1/admin/org-units").param("kind", "department"), OTHER, "AGT"))
                .andExpect(jsonPath("$[?(@.name == 'Бухгалтерия')]").exists());
    }

    @Test
    void anotherTenantDoesNotSeeSetup() throws Exception {
        String org = "{\"name\":\"Тайная " + suffix + "\",\"timezone\":\"Europe/Moscow\",\"locale\":\"ru\"}";
        String etag = mvc.perform(adm(get("/api/v1/admin/setup"))).andReturn().getResponse().getHeader("ETag");
        mvc.perform(adm(put("/api/v1/admin/setup/organization")).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content(org)).andExpect(status().isOk());
        String other = mvc.perform(as(get("/api/v1/admin/setup"), OTHER, "ADM")).andReturn().getResponse()
                .getContentAsString();
        assertThat(other).doesNotContain("Тайная " + suffix);
    }
}
