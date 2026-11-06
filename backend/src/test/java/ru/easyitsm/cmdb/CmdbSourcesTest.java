package ru.easyitsm.cmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Загрузка из источников и сверка (CFG-04, CFG-05): пакеты, повтор пакета, идентификация по правилам класса,
 * приоритет источников и конфликты, дубликаты и слияние, ошибки объектов, удалённые и невиденные объекты.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmdbSourcesTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    final String cfg = Ids.newId().toString();
    final String tag = Ids.newId().toString().substring(24);

    @Test
    void sourcesAndBatchesCreateUpdateAndRepeat() throws Exception {
        mvc.perform(as(post("/api/v1/cmdb/sources"), "AGT")
                        .content("{\"code\":\"x-%s\",\"name\":\"X\",\"kind\":\"discovery\"}".formatted(tag)))
                .andExpect(status().isForbidden());
        String src = source(20);
        mvc.perform(as(post("/api/v1/cmdb/sources"), "CFG")
                        .content("{\"code\":\"%s\",\"name\":\"X\",\"kind\":\"discovery\"}".formatted(src)))
                .andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/cmdb/sources/" + src), "REQ")).andExpect(status().isForbidden());

        // ВМ раньше хоста: связь на хост из того же пакета разрешается вторым проходом.
        String batch = """
                {"batchId":"b1-%1$s","runId":"r1","items":[
                  {"externalId":"vm-1","classCode":"virtual_machine","name":"app-%1$s",
                   "attributes":{"vm_uuid":"uuid-%1$s","cpu_count":4},
                   "relations":[{"typeCode":"runs_on","direction":"outgoing","target":{"externalId":"esx-1"}}]},
                  {"externalId":"esx-1","classCode":"physical_server","name":"esx-%1$s",
                   "attributes":{"serial_number":"sn-esx-%1$s"}}
                ]}""".formatted(tag);
        String first = body(ingest(src, batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.errors").value(0))
                .andExpect(jsonPath("$.results[0].outcome").value("created"))
                .andExpect(jsonPath("$.results[0].warnings.length()").value(0)));
        String vm = JsonPath.read(first, "$.results[0].ciId");
        String esx = JsonPath.read(first, "$.results[1].ciId");

        mvc.perform(as(get("/api/v1/cmdb/cis/" + vm), "AGT"))
                .andExpect(jsonPath("$.attributeSources.vm_uuid").value(src))
                .andExpect(jsonPath("$.lastSeenAt").exists());
        mvc.perform(as(get("/api/v1/cmdb/cis/" + esx), "AGT"))
                .andExpect(jsonPath("$.attributes.serial_number").value(("sn-esx-" + tag).toUpperCase()));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + vm + "/relations"), "AGT"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].other.id").value(esx))
                .andExpect(jsonPath("$[0].origin").value(src));

        // Повтор того же пакета (агент не дождался ответа) возвращает тот же результат и ничего не создаёт.
        ingest(src, batch).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.results[0].ciId").value(vm));
        assertThat(count("select count(*) from ci_source_record r join ci_source s on s.id = r.source_id"
                + " where s.code = '" + src + "'")).isEqualTo(2);
        mvc.perform(as(get("/api/v1/cmdb/sources/" + src + "/batches/b1-" + tag), "AUD"))
                .andExpect(jsonPath("$.created").value(2));
        mvc.perform(as(get("/api/v1/cmdb/sources/" + src), "AUD")).andExpect(jsonPath("$.records").value(2));

        // Второй пакет: атрибут ВМ изменился, связи пустые — связь источника снимается; хост не изменился.
        ingest(src, """
                {"batchId":"b2-%1$s","items":[
                  {"externalId":"vm-1","classCode":"virtual_machine","name":"app-%1$s",
                   "attributes":{"vm_uuid":"uuid-%1$s","cpu_count":8},"relations":[]},
                  {"externalId":"esx-1","classCode":"physical_server","name":"esx-%1$s",
                   "attributes":{"serial_number":"sn-esx-%1$s"}}
                ]}""".formatted(tag))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.results[0].outcome").value("updated"));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + vm), "AGT")).andExpect(jsonPath("$.attributes.cpu_count").value(8));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + vm + "/relations"), "AGT"))
                .andExpect(jsonPath("$.length()").value(0));
        List<String> sources = TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc
                .sql("select distinct source from ci_history where ci_id = cast(:id as uuid)")
                .param("id", vm).query(String.class).list());
        assertThat(sources).containsExactly(src);

        // Источник другого арендатора не виден: 404.
        mvc.perform(as(post("/api/v1/cmdb/sources/" + src + "/batches"), OTHER, "CFG")
                        .content("{\"batchId\":\"b3\",\"items\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void identificationPriorityAndAttributeConflicts() throws Exception {
        String manual = id(mvc.perform(as(post("/api/v1/cmdb/cis"), "CFG").content("""
                {"name":"hw-%1$s","classCode":"physical_server",
                 "attributes":{"serial_number":"SN-%1$s","hostname":"manual-host"}}""".formatted(tag)))
                .andExpect(status().isCreated()));
        String src = source(20);
        // Объект без связки с источником находится по серийному номеру (правило класса), регистр не важен.
        ingest(src, item("p1", "hw-1", "physical_server",
                "{\"serial_number\":\"sn-%s\",\"hostname\":\"disc-host\",\"cpu_count\":16}".formatted(tag)))
                .andExpect(jsonPath("$.results[0].ciId").value(manual))
                .andExpect(jsonPath("$.results[0].outcome").value("updated"))
                .andExpect(jsonPath("$.conflicts").value(1));
        // Ручное значение (приоритет 10) главнее источника с приоритетом 20: пустой атрибут заполнен, имя — конфликт.
        mvc.perform(as(get("/api/v1/cmdb/cis/" + manual), "AGT"))
                .andExpect(jsonPath("$.attributes.hostname").value("manual-host"))
                .andExpect(jsonPath("$.attributes.cpu_count").value(16))
                .andExpect(jsonPath("$.attributeSources.cpu_count").value(src));
        String list = body(mvc.perform(as(get("/api/v1/cmdb/conflicts").param("source", src), "AGT"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("attribute"))
                .andExpect(jsonPath("$.items[0].attribute").value("hostname"))
                .andExpect(jsonPath("$.items[0].currentValue").value("manual-host"))
                .andExpect(jsonPath("$.items[0].proposedValue").value("disc-host"))
                .andExpect(jsonPath("$.items[0].currentSource").value("manual"))
                .andExpect(jsonPath("$.items[0].ci.id").value(manual)));
        String conflict = JsonPath.read(list, "$.items[0].id");

        mvc.perform(as(post("/api/v1/cmdb/conflicts/" + conflict + "/resolve"), "AGT")
                        .content("{\"action\":\"ignore\"}"))
                .andExpect(status().isForbidden());
        resolve(conflict, "{\"action\":\"ignore\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ignored"));
        resolve(conflict, "{\"action\":\"ignore\"}").andExpect(status().isConflict());
        // То же значение после «Игнорировать» конфликт не поднимает, другое — поднимает.
        ingest(src, item("p2", "hw-1", "physical_server", "{\"hostname\":\"disc-host\"}"))
                .andExpect(jsonPath("$.conflicts").value(0));
        String again = body(ingest(src, item("p3", "hw-1", "physical_server", "{\"hostname\":\"disc-host-2\"}"))
                .andExpect(jsonPath("$.conflicts").value(1)));
        assertThat(again).contains("\"outcome\":\"unchanged\"");
        String open = JsonPath.read(body(mvc.perform(as(get("/api/v1/cmdb/conflicts").param("ciId", manual), "AGT"))),
                "$.items[0].id");
        resolve(open, "{\"action\":\"accept\"}").andExpect(jsonPath("$.status").value("accepted"));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + manual), "AGT"))
                .andExpect(jsonPath("$.attributes.hostname").value("disc-host-2"))
                .andExpect(jsonPath("$.attributeSources.hostname").value(src));

        // Источник доверенней ручного ввода (приоритет 5) меняет значения и название без конфликтов.
        String trusted = source(5);
        ingest(trusted, item("t1", "hw-1-renamed", "physical_server",
                "{\"serial_number\":\"SN-%s\",\"hostname\":\"trusted-host\"}".formatted(tag)))
                .andExpect(jsonPath("$.conflicts").value(0))
                .andExpect(jsonPath("$.results[0].ciId").value(manual));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + manual), "AGT"))
                .andExpect(jsonPath("$.attributes.hostname").value("trusted-host"))
                .andExpect(jsonPath("$.name").value("hw-1-renamed-" + tag));
        // Менее доверенный источник, который владел значением раньше, теперь получает конфликт.
        ingest(src, item("p4", "hw-1", "physical_server", "{\"hostname\":\"disc-host-3\"}"))
                .andExpect(jsonPath("$.conflicts").value(1));
    }

    @Test
    void duplicatesRaiseConflictAndMerge() throws Exception {
        String a = server("dup-a", "SN2-" + tag);
        String b = server("dup-b", "SN2-" + tag);
        String svc = id(mvc.perform(as(post("/api/v1/cmdb/cis"), "CFG")
                .content("{\"name\":\"svc-%s\",\"classCode\":\"technical_service\"}".formatted(tag))));
        mvc.perform(as(post("/api/v1/cmdb/cis/" + svc + "/relations"), "CFG")
                        .content("{\"typeCode\":\"depends_on\",\"targetId\":\"%s\"}".formatted(b)))
                .andExpect(status().isCreated());
        String src = source(20);
        ingest(src, item("d1", "hw-dup", "physical_server", "{\"serial_number\":\"sn2-%s\"}".formatted(tag)))
                .andExpect(jsonPath("$.results[0].outcome").value("conflict"))
                .andExpect(jsonPath("$.results[0].code").value("duplicate"))
                .andExpect(jsonPath("$.created").value(0));
        String list = body(mvc.perform(as(get("/api/v1/cmdb/conflicts").param("source", src), "AGT"))
                .andExpect(jsonPath("$.items[0].kind").value("duplicate"))
                .andExpect(jsonPath("$.items[0].candidates.length()").value(2))
                .andExpect(jsonPath("$.items[0].itemName").value("hw-dup-" + tag)));
        String conflict = JsonPath.read(list, "$.items[0].id");
        resolve(conflict, "{\"action\":\"merge\",\"ciId\":\"%s\"}".formatted(Ids.newId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ciId"));
        resolve(conflict, "{\"action\":\"merge\",\"ciId\":\"%s\"}".formatted(a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("merged"));

        // Дубликат выведен и ссылается на остающуюся КЕ, его связь перенесена, объект источника связан с ней.
        mvc.perform(as(get("/api/v1/cmdb/cis/" + b), "AGT"))
                .andExpect(jsonPath("$.status").value("retired"))
                .andExpect(jsonPath("$.mergedInto").value(a));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + a + "/relations"), "AGT"))
                .andExpect(jsonPath("$[0].other.id").value(svc));
        ingest(src, item("d2", "hw-dup", "physical_server", "{\"serial_number\":\"sn2-%s\"}".formatted(tag)))
                .andExpect(jsonPath("$.results[0].ciId").value(a));
        List<String> fields = TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc
                .sql("select field from ci_history where ci_id = cast(:id as uuid)").param("id", a)
                .query(String.class).list());
        assertThat(fields).contains("merged");

        // Ручное слияние с экрана качества: только КЕ одной ветки классов.
        String c = server("dup-c", "SN3-" + tag);
        String d = server("dup-d", "SN3-" + tag);
        mvc.perform(as(post("/api/v1/cmdb/cis/" + c + "/merge"), "CFG").content("{\"mergedId\":\"%s\"}".formatted(svc)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("incompatible_class"));
        mvc.perform(as(post("/api/v1/cmdb/cis/" + c + "/merge"), "AGT").content("{\"mergedId\":\"%s\"}".formatted(d)))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/cmdb/cis/" + c + "/merge"), "CFG").content("{\"mergedId\":\"%s\"}".formatted(d)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(c));
        mvc.perform(as(post("/api/v1/cmdb/cis/" + c + "/merge"), "CFG").content("{\"mergedId\":\"%s\"}".formatted(d)))
                .andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/cmdb/quality/duplicates"), "AGT"))
                .andExpect(jsonPath("$[?(@.value=='SN3-%s')]".formatted(tag.toUpperCase())).isEmpty());
    }

    @Test
    void itemErrorsRemovedAndFullRun() throws Exception {
        String src = source(20);
        String result = body(ingest(src, """
                {"batchId":"e1-%1$s","runId":"run-1","items":[
                  {"externalId":"ok-1","classCode":"physical_server","name":"ok1-%1$s"},
                  {"externalId":"ok-2","classCode":"physical_server","name":"ok2-%1$s"},
                  {"externalId":"bad-1","classCode":"database","name":"db-%1$s",
                   "attributes":{"engine":"password=Sup3rS3cret!42"}},
                  {"externalId":"bad-2","classCode":"no_such_class","name":"x"},
                  {"externalId":"ok-1","classCode":"physical_server","name":"again"},
                  {"externalId":"ok-3","classCode":"physical_server","name":"ok3-%1$s",
                   "relations":[{"typeCode":"runs_on","target":{"externalId":"nowhere"}}]}
                ]}""".formatted(tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.errors").value(3))
                .andExpect(jsonPath("$.results[2].code").value("secret_not_allowed"))
                .andExpect(jsonPath("$.results[2].field").value("attributes.engine"))
                .andExpect(jsonPath("$.results[3].code").value("unknown_class"))
                .andExpect(jsonPath("$.results[4].code").value("duplicate_external_id"))
                .andExpect(jsonPath("$.results[5].warnings[0].code").value("unknown_target")));
        assertThat(result).doesNotContain("Sup3rS3cret");
        String ok1 = JsonPath.read(result, "$.results[0].ciId");
        String ok2 = JsonPath.read(result, "$.results[1].ciId");

        ingest(src, "{\"batchId\":\"e2-%s\",\"final\":true,\"items\":[]}".formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("runId"));
        // Полный обход run-2 видит только ok-2 и ok-3, ok-1 удалён источником.
        ingest(src, """
                {"batchId":"e3-%1$s","runId":"run-2","final":true,"items":[
                  {"externalId":"ok-3","classCode":"physical_server","name":"ok3-%1$s"}],
                 "removed":["ok-1","never-seen"]}""".formatted(tag))
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.results[1].outcome").value("removed"))
                .andExpect(jsonPath("$.results[2].outcome").value("not_found"));
        String stale = body(mvc.perform(as(get("/api/v1/cmdb/quality/issues").param("kind", "stale"), "AGT")));
        assertThat(stale).contains(ok1, ok2).doesNotContain((String) JsonPath.read(result, "$.results[5].ciId"));

        mvc.perform(as(patch("/api/v1/cmdb/sources/" + src), "CFG").header("If-Match", "\"0\"")
                        .content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        ingest(src, "{\"batchId\":\"e4-%s\",\"items\":[]}".formatted(tag)).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- помощники

    String source(int priority) throws Exception {
        String code = "src-" + Ids.newId().toString().substring(24);
        mvc.perform(as(post("/api/v1/cmdb/sources"), "CFG").content("""
                        {"code":"%s","name":"Источник %s","kind":"discovery","priority":%d}"""
                        .formatted(code, code, priority)))
                .andExpect(status().isCreated());
        return code;
    }

    String server(String name, String serial) throws Exception {
        return id(mvc.perform(as(post("/api/v1/cmdb/cis"), "CFG").content("""
                {"name":"%s-%s","classCode":"physical_server","attributes":{"serial_number":"%s"}}"""
                .formatted(name, tag, serial))).andExpect(status().isCreated()));
    }

    String item(String batch, String name, String classCode, String attributes) {
        return """
                {"batchId":"%1$s-%2$s","items":[{"externalId":"%3$s","classCode":"%4$s","name":"%3$s-%2$s",
                 "attributes":%5$s}]}""".formatted(batch, tag, name, classCode, attributes);
    }

    ResultActions ingest(String source, String json) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/sources/" + source + "/batches"), "CFG").content(json));
    }

    ResultActions resolve(String conflict, String json) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/conflicts/" + conflict + "/resolve"), "CFG").content(json));
    }

    long count(String sql) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc.sql(sql).query(Long.class).single());
    }

    static String id(ResultActions r) throws Exception {
        return JsonPath.read(body(r), "$.id");
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String roles) {
        return as(req, DEMO, roles);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String roles) {
        return req.header("X-Dev-User", cfg).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
