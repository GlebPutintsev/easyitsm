package ru.easyitsm.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
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

/** История и состояние на дату (CFG-06), карта (CFG-03), настройка классов (CFG-01) и качество (CFG-07). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmdbHistoryConfigTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;

    final String cfg = Ids.newId().toString();
    final String tag = Ids.newId().toString().substring(24);

    @Test
    void historyAndStateAtDate() throws Exception {
        String app = id(create(ci("app", "application") .replace("}", ",\"attributes\":{\"product_version\":\"1.0\"}}")));
        String srv = id(create(ci("srv", "physical_server")));
        Thread.sleep(5);
        Instant before = Instant.now();
        Thread.sleep(5);
        mvc.perform(as(patch("/api/v1/cmdb/cis/" + app), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                .content("{\"name\":\"app2-%s\",\"status\":\"maintenance\",\"attributes\":{\"product_version\":\"2.0\"}}"
                        .formatted(tag))).andExpect(status().isOk());
        relate(app, "runs_on", srv).andExpect(status().isCreated());

        mvc.perform(as(get("/api/v1/cmdb/cis/" + app).param("at", before.toString()), DEMO, cfg, "AGT"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.name").value("app-" + tag))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.attributes.product_version").value("1.0"))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/relations").param("at", before.toString()), DEMO, cfg,
                "AGT")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/relations"), DEMO, cfg, "AGT"))
                .andExpect(jsonPath("$[0].label").value("Работает на"));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + app).param("at", "2020-01-01T00:00:00Z"), DEMO, cfg, "AGT"))
                .andExpect(status().isNotFound());

        // Снятая связь видна на дату, когда действовала.
        String relId = JsonPath.read(body(mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/relations"), DEMO, cfg,
                "AGT"))), "$[0].id");
        Thread.sleep(5);
        Instant linked = Instant.now();
        Thread.sleep(5);
        mvc.perform(as(delete("/api/v1/cmdb/relations/" + relId), DEMO, cfg, "CFG")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/relations").param("at", linked.toString()), DEMO, cfg,
                "AGT")).andExpect(jsonPath("$.length()").value(1));

        String history = body(mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/history"), DEMO, cfg, "AUD"))
                .andExpect(status().isOk()));
        List<String> fields = JsonPath.read(history, "$.items[*].field");
        assertThat(fields).contains("created", "name", "status", "attr.product_version", "relation");
        assertThat(fields.getFirst()).isEqualTo("relation");
        mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/history"), OTHER, cfg, "AUD"))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapShowsNeighboursBothWaysWithTypeFilter() throws Exception {
        String biz = id(create(ci("biz", "business_service")));
        String app = id(create(ci("app", "application")));
        String vm = id(create(ci("vm", "virtual_machine")));
        String ws = id(create(ci("ws", "workstation")));
        relate(biz, "depends_on", app).andExpect(status().isCreated());
        relate(app, "runs_on", vm).andExpect(status().isCreated());
        relate(ws, "connected_to", vm).andExpect(status().isCreated());

        String map = body(mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/map").param("depth", "2"), DEMO, cfg,
                "AGT")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(map, "$.nodes[*].ci.id")).containsExactlyInAnyOrder(app, biz, vm, ws);
        assertThat(JsonPath.<List<Integer>>read(map, "$.nodes[?(@.ci.id=='" + ws + "')].depth")).containsExactly(2);
        assertThat(JsonPath.<List<Object>>read(map, "$.edges")).hasSize(3);

        String filtered = body(mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/map").param("depth", "5")
                .param("type", "runs_on,depends_on"), DEMO, cfg, "AGT")));
        assertThat(JsonPath.<List<String>>read(filtered, "$.nodes[*].ci.id")).containsExactlyInAnyOrder(app, biz, vm);
        String shallow = body(mvc.perform(as(get("/api/v1/cmdb/cis/" + app + "/map").param("depth", "1"), DEMO, cfg,
                "AGT")));
        assertThat(JsonPath.<List<String>>read(shallow, "$.nodes[*].ci.id")).containsExactlyInAnyOrder(app, biz, vm);
    }

    @Test
    void customClassesAndRelationTypes() throws Exception {
        String code = "router_" + tag.replace("-", "");
        mvc.perform(as(post("/api/v1/cmdb/classes"), DEMO, cfg, "AGT")
                .content("{\"code\":\"%s\",\"name\":\"X\",\"layer\":\"application\"}".formatted(code)))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/cmdb/classes"), DEMO, cfg, "ADM").content("""
                        {"code":"%s","name":"Маршрутизатор","parentCode":"network_device",
                         "attributes":[{"code":"admin_password","name":"Пароль","type":"string"}]}""".formatted(code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("secret_not_allowed"));
        mvc.perform(as(post("/api/v1/cmdb/classes"), DEMO, cfg, "ADM").content("""
                        {"code":"%s","name":"Маршрутизатор","parentCode":"network_device",
                         "identificationRules":[["imei"]]}""".formatted(code)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_rule"));
        mvc.perform(as(post("/api/v1/cmdb/classes"), DEMO, cfg, "ADM").content("""
                        {"code":"%s","name":"Маршрутизатор","parentCode":"network_device",
                         "attributes":[{"code":"ports","name":"Порты","type":"number","required":true},
                                       {"code":"tier","name":"Уровень","type":"enum","options":["core","edge"]}]}"""
                        .formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layer").value("infrastructure"))
                .andExpect(jsonPath("$.attributes[?(@.code=='mac_address')]").exists())
                .andExpect(jsonPath("$.identificationRules[1][0]").value("mac_address"));

        create("{\"name\":\"r-%s\",\"classCode\":\"%s\"}".formatted(tag, code))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("attribute_required"));
        create("{\"name\":\"r-%s\",\"classCode\":\"%s\",\"attributes\":{\"ports\":24,\"tier\":\"mid\"}}"
                .formatted(tag, code))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("tier"));
        create("{\"name\":\"r-%s\",\"classCode\":\"%s\",\"attributes\":{\"ports\":24,\"tier\":\"core\"}}"
                .formatted(tag, code)).andExpect(status().isCreated());

        mvc.perform(as(patch("/api/v1/cmdb/classes/" + code), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                .content("{\"name\":\"Маршрутизатор ядра\"}")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        mvc.perform(as(patch("/api/v1/cmdb/classes/server"), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                .content("{\"attributes\":[]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("system_attribute"));

        String rel = "backs_up_" + tag.replace("-", "");
        mvc.perform(as(post("/api/v1/cmdb/relation-types"), DEMO, cfg, "CFG").content("""
                        {"code":"%s","name":"Резервирует","inverseName":"Резервируется","impactDirection":"none"}"""
                        .formatted(rel))).andExpect(status().isCreated());
        mvc.perform(as(patch("/api/v1/cmdb/relation-types/runs_on"), DEMO, cfg, "CFG")
                .content("{\"impactDirection\":\"none\"}")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/cmdb/relation-types"), OTHER, cfg, "CFG"))
                .andExpect(jsonPath("$[?(@.code=='" + rel + "')]").isEmpty());
    }

    @Test
    void qualityFindsOrphansDuplicatesAndGaps() throws Exception {
        String serial = "SN-" + tag;
        String a = id(create("{\"name\":\"dup-%s\",\"classCode\":\"physical_server\",\"attributes\":{\"serial_number\":\"%s\"}}"
                .formatted(tag, serial.toLowerCase())));
        String b = id(create("{\"name\":\"dup2-%s\",\"classCode\":\"physical_server\",\"attributes\":{\"serial_number\":\"%s\"}}"
                .formatted(tag, serial)));
        String c = id(create(ci("svc", "business_service")));

        String dups = body(mvc.perform(as(get("/api/v1/cmdb/quality/duplicates"), DEMO, cfg, "CFG"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(dups,
                "$[?(@.value=='" + serial.toUpperCase() + "')].cis[*].id")).containsExactlyInAnyOrder(a, b);
        String orphans = body(mvc.perform(as(get("/api/v1/cmdb/quality/issues").param("kind", "orphan"), DEMO, cfg,
                "CFG")));
        assertThat(JsonPath.<List<String>>read(orphans, "$[*].id")).contains(a, b, c);
        mvc.perform(as(get("/api/v1/cmdb/quality"), DEMO, cfg, "CFG")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.score").isNumber());
        mvc.perform(as(get("/api/v1/cmdb/quality/issues").param("kind", "nope"), DEMO, cfg, "CFG"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- помощники

    String ci(String name, String classCode) {
        return "{\"name\":\"%s-%s\",\"classCode\":\"%s\"}".formatted(name, tag, classCode);
    }

    ResultActions create(String json) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/cis"), DEMO, cfg, "CFG").content(json));
    }

    ResultActions relate(String from, String type, String to) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/cis/" + from + "/relations"), DEMO, cfg, "CFG")
                .content("{\"typeCode\":\"%s\",\"targetId\":\"%s\"}".formatted(type, to)));
    }

    static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String user,
                                            String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
