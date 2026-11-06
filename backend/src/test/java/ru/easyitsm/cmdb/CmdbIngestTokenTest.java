package ru.easyitsm.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;

/**
 * Токен источника для агента обнаружения (CFG-04): выпуск и отзыв, приём пакета без входа пользователя,
 * повтор пакета, выключенный источник, токен действует только в своём арендаторе и источнике.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmdbIngestTokenTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;

    final String cfg = Ids.newId().toString();
    final String tag = Ids.newId().toString().substring(24);

    @Test
    void agentSendsBatchesWithSourceToken() throws Exception {
        String src = source(DEMO);
        mvc.perform(as(post("/api/v1/cmdb/sources/" + src + "/token"), DEMO, "AGT"))
                .andExpect(status().isForbidden());
        String issued = body(mvc.perform(as(post("/api/v1/cmdb/sources/" + src + "/token"), DEMO, "CFG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(src))
                .andExpect(jsonPath("$.tokenIssuedAt").exists()));
        String token = JsonPath.read(issued, "$.token");
        assertThat(token).startsWith("eitsm_cmdb_");
        mvc.perform(as(get("/api/v1/cmdb/sources/" + src), DEMO, "AGT"))
                .andExpect(jsonPath("$.tokenHint").value("…" + token.substring(token.length() - 4)))
                .andExpect(jsonPath("$.token").doesNotExist());

        // Агент проверяет настройку и узнаёт свой источник.
        mvc.perform(agent(get("/api/v1/cmdb/ingest/source"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(src))
                .andExpect(jsonPath("$.active").value(true));

        String batch = """
                {"batchId":"agent-%1$s-1","runId":"agent-%1$s","items":[
                  {"externalId":"host-%1$s","classCode":"virtual_machine","name":"vm-%1$s",
                   "attributes":{"hostname":"vm-%1$s","vm_uuid":"uuid-%1$s","cpu_count":2}}]}""".formatted(tag);
        String first = body(mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("created")));
        String ci = JsonPath.read(first, "$.results[0].ciId");
        mvc.perform(as(get("/api/v1/cmdb/cis/" + ci), DEMO, "AGT"))
                .andExpect(jsonPath("$.attributeSources.vm_uuid").value(src));

        // Повтор того же пакета после обрыва связи безопасен: тот же результат, ничего не меняется.
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].ciId").value(ci));

        // Токен — не вход пользователя: остальное API ему закрыто.
        mvc.perform(agent(get("/api/v1/cmdb/cis/" + ci), token)).andExpect(status().isUnauthorized());
        mvc.perform(agent(post("/api/v1/cmdb/sources/" + src + "/batches"), token).content(batch))
                .andExpect(status().isUnauthorized());

        // Тело проверяется так же, как у пакета от пользователя.
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_batch"))
                .andExpect(jsonPath("$.field").value("batchId"));
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content("не json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_batch"));

        // Выключенный источник не принимает пакеты.
        mvc.perform(as(patch("/api/v1/cmdb/sources/" + src), DEMO, "CFG").header("If-Match", etag(src))
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token)
                        .content("{\"batchId\":\"agent-%s-2\",\"items\":[]}".formatted(tag)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("source_inactive"));

        // Новый токен сразу отменяет прежний, отзыв — любой.
        String second = JsonPath.read(body(mvc.perform(as(post("/api/v1/cmdb/sources/" + src + "/token"), DEMO, "ADM"))
                .andExpect(status().isOk())), "$.token");
        mvc.perform(agent(get("/api/v1/cmdb/ingest/source"), token)).andExpect(status().isUnauthorized());
        mvc.perform(agent(get("/api/v1/cmdb/ingest/source"), second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(as(delete("/api/v1/cmdb/sources/" + src + "/token"), DEMO, "CFG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenHint").doesNotExist());
        mvc.perform(agent(get("/api/v1/cmdb/ingest/source"), second)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWorksOnlyInItsTenantAndRejectsGarbage() throws Exception {
        String other = source(OTHER);
        String token = JsonPath.read(body(mvc.perform(as(post("/api/v1/cmdb/sources/" + other + "/token"), OTHER,
                "CFG")).andExpect(status().isOk())), "$.token");
        String batch = """
                {"batchId":"t-%1$s","items":[{"externalId":"h-%1$s","classCode":"server","name":"srv-%1$s"}]}"""
                .formatted(tag);
        String ci = JsonPath.read(body(mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content(batch))
                .andExpect(status().isOk())), "$.results[0].ciId");
        mvc.perform(as(get("/api/v1/cmdb/cis/" + ci), DEMO, "AGT")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/cmdb/cis/" + ci), OTHER, "AGT")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/cmdb/ingest/batches").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), "eitsm_cmdb_wrong").content(batch))
                .andExpect(status().isUnauthorized());
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), "eitsm_mon_" + token.substring(11)).content(batch))
                .andExpect(status().isUnauthorized());
    }

    String source(String tenant) throws Exception {
        String code = "agent-" + Ids.newId().toString().substring(24);
        mvc.perform(as(post("/api/v1/cmdb/sources"), tenant, "CFG").content("""
                        {"code":"%s","name":"Агент %s","kind":"discovery","priority":20}""".formatted(code, code)))
                .andExpect(status().isCreated());
        return code;
    }

    String etag(String src) throws Exception {
        return mvc.perform(as(get("/api/v1/cmdb/sources/" + src), DEMO, "CFG")).andReturn().getResponse()
                .getHeader("ETag");
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static MockHttpServletRequestBuilder agent(MockHttpServletRequestBuilder req, String token) {
        return req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String roles) {
        return req.header("X-Dev-User", cfg).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
