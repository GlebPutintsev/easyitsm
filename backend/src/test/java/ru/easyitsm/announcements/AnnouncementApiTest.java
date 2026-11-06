package ru.easyitsm.announcements;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Сквозная проверка соглашений API на эталонном модуле: права, ETag, идемпотентность, 404 для чужих записей. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";
    static final String BODY = """
            {"kind":"maintenance","title":"Обновление почты","body":"Почта недоступна 10 минут",
             "startsAt":"2026-11-05T20:00:00Z","endsAt":"2026-11-05T21:00:00Z","published":true}""";

    /** Один пользователь на все запросы: ключ идемпотентности уникален в пределах пользователя. */
    static final String USER = Ids.newId().toString();

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/v1/announcements")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceIdsAreStoredAndReplaced() throws Exception {
        String a = Ids.newId().toString();
        String b = Ids.newId().toString();
        String body = BODY.replace("\"published\":true", "\"published\":true,\"serviceIds\":[\"" + a + "\",\"" + a + "\"]");
        String created = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.serviceIds.length()").value(1))
                .andExpect(jsonPath("$.serviceIds[0]").value(a))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(as(patch("/api/v1/announcements/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"serviceIds\":[\"" + b + "\"]}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/announcements/" + id), DEMO, "REQ"))
                .andExpect(jsonPath("$.serviceIds[0]").value(b))
                .andExpect(jsonPath("$.serviceIds.length()").value(1));
        // Без поля — пустой список: объявление для всех
        String plain = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(get("/api/v1/announcements/" + JsonPath.read(plain, "$.id")), DEMO, "REQ"))
                .andExpect(jsonPath("$.serviceIds.length()").value(0));
    }

    @Test
    void requesterCannotCreate() throws Exception {
        mvc.perform(as(post("/api/v1/announcements"), DEMO, "REQ").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"))
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void createReadUpdateWithEtag() throws Exception {
        String created = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(as(get("/api/v1/announcements/" + id), DEMO, "REQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Обновление почты"));

        String patchBody = "{\"title\":\"Обновление почты перенесено\"}";
        mvc.perform(as(patch("/api/v1/announcements/" + id), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch("/api/v1/announcements/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        mvc.perform(as(patch("/api/v1/announcements/" + id), DEMO, "ADM").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("version_conflict"));
    }

    @Test
    void anotherTenantGets404() throws Exception {
        String created = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mvc.perform(as(get("/api/v1/announcements/" + id), OTHER, "ADM"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void idempotentCreateReturnsSameResult() throws Exception {
        String key = "test-" + UUID.randomUUID();
        String first = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(JsonPath.<String>read(second, "$.id"))
                .isEqualTo(JsonPath.<String>read(first, "$.id"));

        mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("почты", "VPN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void validationErrorIsProblemJson() throws Exception {
        mvc.perform(as(post("/api/v1/announcements"), DEMO, "ADM")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"unknown\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.trace_id").exists());
    }

    @Test
    void invalidLimitIsRejected() throws Exception {
        mvc.perform(as(get("/api/v1/announcements").param("limit", "500"), DEMO, "REQ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_limit"));
    }

    @Test
    void pagesWithCursor() throws Exception {
        String tenant = OTHER;
        for (int i = 0; i < 3; i++) {
            mvc.perform(as(post("/api/v1/announcements"), tenant, "ADM").contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("2026-11-05T20", "2030-01-0" + (i + 1) + "T20")
                            .replace("2026-11-05T21", "2030-01-0" + (i + 1) + "T21")));
        }
        String page1 = mvc.perform(as(get("/api/v1/announcements").param("limit", "2"), tenant, "REQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        mvc.perform(as(get("/api/v1/announcements").param("limit", "2").param("cursor", cursor), tenant, "REQ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].startsAt").exists());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String roles) {
        return req.header("X-Dev-User", USER).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
    }
}
