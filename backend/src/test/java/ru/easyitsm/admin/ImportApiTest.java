package ru.easyitsm.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.easyitsm.platform.tenant.TenantContext;

/** Импорт CSV с предпросмотром, проверкой и откатом (ADM-04). */
class ImportApiTest extends AdminApiSupport {

    String upload(String kind, String csv) throws Exception {
        String body = new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                Map.of("kind", kind, "fileName", kind + ".csv", "content", csv));
        return mvc.perform(adm(post("/api/v1/admin/imports")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    long usersWithLogin(String login) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc
                .sql("select count(*) from user_account where lower(login) = lower(:l)").param("l", login)
                .query(Long.class).single());
    }

    @Test
    void usersPreviewApplyAndRollback() throws Exception {
        String group = "Импорт " + suffix;
        String groupsJob = upload("groups", "name;type;description\n" + group + ";support;Из файла\n");
        mvc.perform(adm(post("/api/v1/admin/imports/" + str(groupsJob, "$.id") + "/apply")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("applied"));

        String a = "imp.a." + suffix;
        String b = "imp.b." + suffix;
        String csv = "﻿Логин;ФИО;Почта;Подразделение;Группы\n"
                + a + ";Анна Импорт;" + a + "@example.org;Склад " + suffix + ";" + group + "\n"
                + b + ";\"Борис \"\"Импорт\"\"\";;Склад " + suffix + ";\n"
                + "bad login;Плохой;not-mail;;Нет такой группы\n"
                + a + ";Повтор;;;\n";
        String job = upload("users", csv);
        assertThat(JsonPath.<Integer>read(job, "$.totalRows")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(job, "$.validRows")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(job, "$.errorRows")).isEqualTo(2);
        assertThat(JsonPath.<String>read(job, "$.rows[1].values.display_name")).isEqualTo("Борис \"Импорт\"");
        List<String> errors = JsonPath.read(job, "$.rows[2].errors");
        assertThat(errors).anyMatch(e -> e.contains("Логин")).anyMatch(e -> e.contains("почта"))
                .anyMatch(e -> e.contains("Нет такой группы"));
        assertThat(JsonPath.<List<String>>read(job, "$.rows[0].warnings")).anyMatch(w -> w.contains("будет создано"));
        assertThat(usersWithLogin(a)).isZero();

        String id = str(job, "$.id");
        mvc.perform(adm(post("/api/v1/admin/imports/" + id + "/apply")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("has_errors"));
        mvc.perform(adm(post("/api/v1/admin/imports/" + id + "/apply")).param("skipInvalid", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.result.user").value(2))
                .andExpect(jsonPath("$.result.membership").value(1))
                .andExpect(jsonPath("$.result.department").value(1));
        assertThat(usersWithLogin(a)).isOne();
        mvc.perform(adm(post("/api/v1/admin/imports/" + id + "/apply")).param("skipInvalid", "true"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_in_preview"));

        mvc.perform(adm(post("/api/v1/admin/imports/" + id + "/rollback")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rolled_back"))
                .andExpect(jsonPath("$.result.removedUsers").value(2))
                .andExpect(jsonPath("$.result.removedDepartments").value(1));
        assertThat(usersWithLogin(a)).isZero();
        mvc.perform(adm(post("/api/v1/admin/imports/" + id + "/rollback")))
                .andExpect(status().isConflict());

        mvc.perform(adm(get("/api/v1/admin/config-versions").param("entity", "admin.import").param("entityId", id)))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(as(get("/api/v1/admin/imports/" + id), OTHER, "ADM")).andExpect(status().isNotFound());
        mvc.perform(adm(get("/api/v1/admin/imports"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").exists());
    }

    @Test
    void existingRecordsAreSkippedAndDepartmentsNest() throws Exception {
        String parent = "Дирекция " + suffix;
        String csv = "name,code,parent\n" + parent + ",D" + suffix + ",\nОтдел " + suffix + ",,"
                + parent + "\nСирота " + suffix + ",,Нет такого\n";
        String job = upload("departments", csv);
        assertThat(JsonPath.<Integer>read(job, "$.validRows")).isEqualTo(2);
        assertThat(JsonPath.<String>read(job, "$.rows[2].action")).isEqualTo("error");
        mvc.perform(adm(post("/api/v1/admin/imports/" + str(job, "$.id") + "/apply")).param("skipInvalid", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.department").value(2));
        String again = upload("departments", "name\n" + parent + "\n");
        assertThat(JsonPath.<String>read(again, "$.rows[0].action")).isEqualTo("skip");
        mvc.perform(adm(get("/api/v1/admin/org-units").param("q", "Отдел " + suffix)))
                .andExpect(jsonPath("$[0].parentId").isNotEmpty());
    }

    @Test
    void badFilesAreRejected() throws Exception {
        String noColumn = new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                Map.of("kind", "users", "content", "email\na@b.c\n"));
        mvc.perform(adm(post("/api/v1/admin/imports")).contentType(MediaType.APPLICATION_JSON).content(noColumn))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("missing_column"));
        String quote = new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                Map.of("kind", "groups", "content", "name\n\"Незакрытая\n"));
        mvc.perform(adm(post("/api/v1/admin/imports")).contentType(MediaType.APPLICATION_JSON).content(quote))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_csv"));
    }
}
