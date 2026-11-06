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

    /** Пакеты в том виде, в каком их шлёт discovery-agent: сервер с базой, обход сети и завершающий пакет. */
    @Test
    void agentShapedRunsAndFullSweep() throws Exception {
        String src = source(DEMO);
        String token = JsonPath.read(body(mvc.perform(as(post("/api/v1/cmdb/sources/" + src + "/token"), DEMO, "CFG"))
                .andExpect(status().isOk())), "$.token");
        String host = "host:" + tag;
        String run1 = """
                {"batchId":"a-%1$s-r1-0001","runId":"a-%1$s-r1","items":[
                  {"externalId":"%2$s","classCode":"virtual_machine","name":"app-%1$s","status":"active",
                   "environment":"production","attributes":{"hostname":"app-%1$s","domain":"corp.local",
                   "fqdn":"app-%1$s.corp.local","ip_address":"10.9.0.10","os":"Astra Linux","os_version":"1.8_x86-64",
                   "cpu_count":4,"ram_gb":15.5,"vm_uuid":"vm-%1$s","hypervisor":"VMware"}},
                  {"externalId":"%2$s/db/5432","classCode":"database","name":"PostgreSQL app-%1$s:5432",
                   "status":"active","environment":"production","attributes":{"engine":"PostgreSQL","port":5432},
                   "relations":[{"typeCode":"runs_on","direction":"outgoing","target":{"externalId":"%2$s"}}]},
                  {"externalId":"ip:10.9.0.1","classCode":"network_device","name":"sw-%1$s","status":"active",
                   "attributes":{"hostname":"sw-%1$s","ip_address":"10.9.0.1"}},
                  {"externalId":"ip:10.9.0.2","classCode":"server","name":"10.9.0.2","status":"active",
                   "attributes":{"ip_address":"10.9.0.2"}}]}""".formatted(tag, host);
        String first = body(mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content(run1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(4))
                .andExpect(jsonPath("$.errors").value(0))
                .andExpect(jsonPath("$.results[1].warnings.length()").value(0)));
        String vm = JsonPath.read(first, "$.results[0].ciId");
        String db = JsonPath.read(first, "$.results[1].ciId");
        String gone = JsonPath.read(first, "$.results[3].ciId");
        mvc.perform(as(get("/api/v1/cmdb/cis/" + db + "/relations"), DEMO, "AGT"))
                .andExpect(jsonPath("$[0].typeCode").value("runs_on"))
                .andExpect(jsonPath("$[0].other.id").value(vm));
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token)
                        .content("{\"batchId\":\"a-%1$s-r1-final\",\"runId\":\"a-%1$s-r1\",\"final\":true,\"items\":[]}"
                                .formatted(tag)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.final").value(true));

        // Второй обход не нашёл 10.9.0.2: после завершающего пакета узел неактуален.
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token).content("""
                        {"batchId":"a-%1$s-r2-0001","runId":"a-%1$s-r2","items":[
                          {"externalId":"ip:10.9.0.1","classCode":"network_device","name":"sw-%1$s",
                           "attributes":{"hostname":"sw-%1$s","ip_address":"10.9.0.1"}},
                          {"externalId":"%2$s","classCode":"virtual_machine","name":"app-%1$s",
                           "attributes":{"hostname":"app-%1$s","ram_gb":15.5}}]}""".formatted(tag, host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unchanged").value(2));
        mvc.perform(agent(post("/api/v1/cmdb/ingest/batches"), token)
                        .content("{\"batchId\":\"a-%1$s-r2-final\",\"runId\":\"a-%1$s-r2\",\"final\":true,\"items\":[]}"
                                .formatted(tag)))
                .andExpect(status().isOk());
        String stale = body(mvc.perform(as(get("/api/v1/cmdb/quality/issues").param("kind", "stale"), DEMO, "AGT")));
        assertThat(stale).contains(gone).doesNotContain(vm);
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
