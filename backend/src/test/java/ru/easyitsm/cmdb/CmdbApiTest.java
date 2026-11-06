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
import ru.easyitsm.cmdb.api.CiDirectory;
import ru.easyitsm.cmdb.api.CiImpact;
import ru.easyitsm.cmdb.api.CiLayer;
import ru.easyitsm.cmdb.api.CiRef;
import ru.easyitsm.cmdb.api.CiSearch;
import ru.easyitsm.cmdb.api.ImpactReport;
import ru.easyitsm.cmdb.api.ImpactedCi;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** REST и Java API CMDB: права, ETag, атрибуты по классу, запрет секретов, связи, влияние, изоляция. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmdbApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CiDirectory directory;
    @Autowired
    CiImpact impact;

    final String cfg = Ids.newId().toString();
    final String tag = Ids.newId().toString().substring(24);

    @Test
    void readAndWriteRoles() throws Exception {
        mvc.perform(get("/api/v1/cmdb/cis")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/cmdb/cis"), DEMO, Ids.newId().toString(), "REQ"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/cmdb/cis"), DEMO, Ids.newId().toString(), "AUD")).andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/cmdb/cis"), DEMO, Ids.newId().toString(), "AGT")
                        .content("{\"name\":\"srv\",\"classCode\":\"physical_server\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void classesInheritAttributesAndRules() throws Exception {
        String json = body(mvc.perform(as(get("/api/v1/cmdb/classes"), DEMO, cfg, "CFG")).andExpect(status().isOk()));
        List<String> vmAttrs = JsonPath.read(json, "$[?(@.code=='virtual_machine')].attributes[*].code");
        assertThat(vmAttrs).contains("serial_number", "hostname", "fqdn", "vm_uuid");
        List<List<List<String>>> rules = JsonPath.read(json,
                "$[?(@.code=='physical_server')].identificationRules");
        assertThat(rules.getFirst().getFirst()).containsExactly("serial_number");
        List<String> layers = JsonPath.read(json, "$[?(@.code=='technical_service')].layer");
        assertThat(layers).containsExactly("technical_service");
        mvc.perform(as(get("/api/v1/cmdb/relation-types"), DEMO, cfg, "CFG"))
                .andExpect(jsonPath("$[?(@.code=='runs_on')].impactDirection").value("target_to_source"));
    }

    @Test
    void createReadUpdateWithEtagAndHistory() throws Exception {
        String created = body(mvc.perform(as(post("/api/v1/cmdb/cis"), DEMO, cfg, "CFG").content("""
                        {"name":"srv-%s","classCode":"virtual_machine","environment":"production",
                         "criticality":"high","attributes":{"hostname":"SRV-01","ip_address":"10.0.0.5",
                         "cpu_count":4}}""".formatted(tag)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.layer").value("infrastructure"))
                .andExpect(jsonPath("$.attributes.hostname").value("srv-01"))
                .andExpect(jsonPath("$.attributeSources.hostname").value("manual")));
        String id = JsonPath.read(created, "$.id");

        mvc.perform(as(patch("/api/v1/cmdb/cis/" + id), DEMO, cfg, "CFG").content("{\"status\":\"maintenance\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch("/api/v1/cmdb/cis/" + id), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                        .content("{\"status\":\"maintenance\",\"environment\":null,"
                                + "\"attributes\":{\"cpu_count\":8,\"ip_address\":null}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("maintenance"))
                .andExpect(jsonPath("$.environment").doesNotExist())
                .andExpect(jsonPath("$.attributes.cpu_count").value(8))
                .andExpect(jsonPath("$.attributes.ip_address").doesNotExist());
        mvc.perform(as(patch("/api/v1/cmdb/cis/" + id), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isPreconditionFailed());

        List<String> fields = TenantContext.callAs(UUID.fromString(DEMO), () -> jdbc
                .sql("select field from ci_history where ci_id = cast(:id as uuid) order by at, field")
                .param("id", id).query(String.class).list());
        assertThat(fields).contains("created", "status", "environment", "attr.cpu_count", "attr.ip_address");

        mvc.perform(as(get("/api/v1/cmdb/cis").param("q", "SRV-" + tag), DEMO, cfg, "AGT"))
                .andExpect(jsonPath("$.items[0].id").value(id));
        mvc.perform(as(get("/api/v1/cmdb/cis").param("classCode", "server").param("q", tag), DEMO, cfg, "AGT"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(as(get("/api/v1/cmdb/cis").param("classCode", "network_device").param("q", tag), DEMO, cfg,
                        "AGT"))
                .andExpect(jsonPath("$.items.length()").value(0));

        // Чужой арендатор не видит КЕ: 404, как будто её нет (AT-01).
        mvc.perform(as(get("/api/v1/cmdb/cis/" + id), OTHER, cfg, "CFG")).andExpect(status().isNotFound());
        assertThat(TenantContext.callAs(UUID.fromString(OTHER), () -> directory.find(UUID.fromString(id))))
                .isEmpty();
    }

    @Test
    void attributesFollowClassSchemaAndRejectSecrets() throws Exception {
        create("{\"name\":\"grp-%s\",\"classCode\":\"infrastructure\"}".formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("abstract_class"));
        create("{\"name\":\"x-%s\",\"classCode\":\"server\",\"attributes\":{\"color\":\"red\"}}".formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_attribute"))
                .andExpect(jsonPath("$.field").value("color"));
        create("{\"name\":\"x-%s\",\"classCode\":\"server\",\"attributes\":{\"cpu_count\":\"много\"}}".formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_attribute"));
        create("{\"name\":\"x-%s\",\"classCode\":\"server\",\"attributes\":{\"ip_address\":\"10.0.0.300\"}}"
                .formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ip_address"));
        // CFG-08: пароль в описании или атрибуте не сохраняется, значение не возвращается в ответе.
        String body = body(create("""
                {"name":"db-%s","classCode":"database","description":"Вход: password=Sup3rS3cret!42"}""".formatted(tag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("secret_not_allowed"))
                .andExpect(jsonPath("$.field").value("description")));
        assertThat(body).doesNotContain("Sup3rS3cret");
        create("""
                {"name":"db-%s","classCode":"database","attributes":{"engine":"password=Sup3rS3cret!42"}}"""
                .formatted(tag))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("attributes.engine"));
    }

    @Test
    void impactFollowsRelationDirectionAndSkipsRetired() throws Exception {
        String server = id(create(ci("srv", "physical_server")));
        String vm = id(create(ci("vm", "virtual_machine")));
        String app = id(create(ci("app", "application")));
        String db = id(create(ci("db", "database")));
        String tech = id(create(ci("tech", "technical_service")));
        String biz = id(create(ci("biz", "business_service")));
        String lonely = id(create(ci("lonely", "workstation")));

        relate(server, "hosts", vm, "outgoing").andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Размещает"));
        relate(app, "runs_on", vm, "outgoing").andExpect(status().isCreated());
        // со стороны цели: «Нужна для» — обратное название
        relate(app, "depends_on", tech, "incoming").andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Нужна для"));
        relate(biz, "depends_on", tech, "outgoing").andExpect(status().isCreated());
        relate(app, "depends_on", db, "outgoing").andExpect(status().isCreated());
        relate(lonely, "connected_to", server, "outgoing").andExpect(status().isCreated());
        relate(app, "runs_on", vm, "outgoing").andExpect(status().isConflict());
        relate(app, "runs_on", app, "outgoing").andExpect(status().isBadRequest());

        ImpactReport report = TenantContext.callAs(UUID.fromString(DEMO),
                () -> impact.analyze(List.of(UUID.fromString(server)), 5));
        assertThat(report.impacted()).extracting(i -> i.ci().id().toString(), ImpactedCi::depth)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(vm, 1), org.assertj.core.groups.Tuple.tuple(app, 2),
                        org.assertj.core.groups.Tuple.tuple(tech, 3), org.assertj.core.groups.Tuple.tuple(biz, 4));
        assertThat(report.services()).extracting(i -> i.ci().id().toString()).containsExactly(tech, biz);
        assertThat(report.services().get(1).path()).extracting(UUID::toString)
                .containsExactly(server, vm, app, tech, biz);
        // connected_to не передаёт влияние, база данных не зависит от приложения
        assertThat(report.impacted()).extracting(i -> i.ci().id().toString()).doesNotContain(lonely, db);

        ImpactReport shallow = TenantContext.callAs(UUID.fromString(DEMO),
                () -> impact.analyze(List.of(UUID.fromString(server)), 2));
        assertThat(shallow.services()).isEmpty();
        assertThat(TenantContext.callAs(UUID.fromString(DEMO),
                () -> impact.impactedServices(List.of(UUID.fromString(db)), 5)))
                .extracting(i -> i.ci().id().toString()).containsExactly(tech, biz);

        // Выведенная из эксплуатации ВМ влияние не передаёт.
        mvc.perform(as(patch("/api/v1/cmdb/cis/" + vm), DEMO, cfg, "CFG").header("If-Match", "\"0\"")
                .content("{\"status\":\"retired\"}")).andExpect(status().isOk());
        ImpactReport afterRetire = TenantContext.callAs(UUID.fromString(DEMO),
                () -> impact.analyze(List.of(UUID.fromString(server)), 5));
        assertThat(afterRetire.impacted()).isEmpty();

        // Снятая связь пропадает из списка и из влияния, но остаётся в истории.
        String relations = body(mvc.perform(as(get("/api/v1/cmdb/cis/" + db + "/relations"), DEMO, cfg, "AGT")));
        String relId = JsonPath.read(relations, "$[0].id");
        assertThat((String) JsonPath.read(relations, "$[0].label")).isEqualTo("Нужна для");
        mvc.perform(as(delete("/api/v1/cmdb/relations/" + relId), DEMO, cfg, "AGT")).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/v1/cmdb/relations/" + relId), DEMO, cfg, "CFG"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/cmdb/cis/" + db + "/relations"), DEMO, cfg, "AGT"))
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(TenantContext.callAs(UUID.fromString(DEMO),
                () -> impact.impactedServices(List.of(UUID.fromString(db)), 5))).isEmpty();
    }

    @Test
    void impactShowsOnlyVisibleOpenTickets() throws Exception {
        String server = id(create(ci("srv", "physical_server")));
        String svc = id(create(ci("svc", "business_service")));
        relate(svc, "depends_on", server, "outgoing").andExpect(status().isCreated());

        String agent = Ids.newId().toString();
        String stranger = Ids.newId().toString();
        String group = group(agent);
        String requester = Ids.newId().toString();
        String ticket = id(mvc.perform(as(post("/api/v1/tickets"), DEMO, requester, "REQ").content(
                "{\"type\":\"incident\",\"title\":\"Не работает %s\",\"description\":\"-\",\"serviceId\":\"%s\"}"
                        .formatted(tag, svc))).andExpect(status().isCreated()));
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql(
                        "update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", group).param("id", ticket).update());

        mvc.perform(as(get("/api/v1/cmdb/cis/" + server + "/impact"), DEMO, agent, "AGT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].ci.id").value(svc))
                .andExpect(jsonPath("$.services[0].ci.layer").value("business_service"))
                .andExpect(jsonPath("$.tickets[0].id").value(ticket))
                .andExpect(jsonPath("$.tickets[0].type").value("incident"))
                .andExpect(jsonPath("$.hiddenTickets").value(0));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + server + "/impact"), DEMO, stranger, "AGT"))
                .andExpect(jsonPath("$.tickets.length()").value(0))
                .andExpect(jsonPath("$.hiddenTickets").value(1));
        mvc.perform(as(get("/api/v1/cmdb/cis/" + server + "/impact"), OTHER, agent, "AGT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directorySearchForForms() throws Exception {
        String svc = id(create(ci("Почта", "business_service")));
        String srv = id(create(ci("Почта-сервер", "physical_server")));
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            List<CiRef> services = directory.search(CiSearch.services("почта-" + tag, 10));
            assertThat(services).extracting(c -> c.id().toString()).containsExactly(svc);
            assertThat(services.getFirst().isService()).isTrue();
            assertThat(services.getFirst().layer()).isEqualTo(CiLayer.BUSINESS_SERVICE);
            List<CiRef> servers = directory.search(new CiSearch(tag, java.util.Set.of("server"), null, false, 10));
            assertThat(servers).extracting(c -> c.id().toString()).containsExactly(srv);
            assertThat(directory.search(CiSearch.text(srv, 5))).hasSize(1);
            assertThat(directory.findAll(List.of(UUID.fromString(srv), Ids.newId(), UUID.fromString(svc))))
                    .extracting(c -> c.id().toString()).containsExactly(srv, svc);
        });
    }

    // ---------------------------------------------------------------- помощники

    String ci(String name, String classCode) {
        return "{\"name\":\"%s-%s\",\"classCode\":\"%s\"}".formatted(name, tag, classCode);
    }

    ResultActions create(String json) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/cis"), DEMO, cfg, "CFG").content(json));
    }

    ResultActions relate(String from, String type, String to, String direction) throws Exception {
        return mvc.perform(as(post("/api/v1/cmdb/cis/" + from + "/relations"), DEMO, cfg, "CFG")
                .content("{\"typeCode\":\"%s\",\"targetId\":\"%s\",\"direction\":\"%s\"}"
                        .formatted(type, to, direction)));
    }

    String group(String member) {
        String group = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(DEMO), () -> {
            jdbc.sql("""
                    insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                    values (cast(:id as uuid), cast(:t as uuid), :login, 'Агент', now(), now(), 0)""")
                    .param("id", member).param("t", DEMO).param("login", "c" + member.replace("-", "")).update();
            jdbc.sql("""
                    insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                    values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + group).update();
            jdbc.sql("""
                    insert into group_member (tenant_id, group_id, user_id)
                    values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                    .param("t", DEMO).param("g", group).param("u", member).update();
        });
        return group;
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
