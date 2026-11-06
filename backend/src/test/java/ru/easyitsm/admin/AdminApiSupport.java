package ru.easyitsm.admin;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Общие помощники API-тестов «Управления». Данные арендатора между запусками не очищаются, поэтому тесты
 * берут текущую версию из ответа и заводят записи с уникальными именами.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AdminApiSupport {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    final String admin = Ids.newId().toString();
    final String suffix = Long.toString(System.nanoTime(), 36);

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String roles) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles);
    }

    MockHttpServletRequestBuilder adm(MockHttpServletRequestBuilder req) {
        return as(req, DEMO, "ADM");
    }

    static String str(String json, String path) {
        return JsonPath.read(json, path).toString();
    }

    /** Пользователь в справочнике identity (для членства в группах). */
    String user(String tenant) {
        String id = Ids.newId().toString();
        TenantContext.runAs(UUID.fromString(tenant), () -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)""")
                .param("id", id).param("t", tenant).param("login", "u" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
        return id;
    }

    String group(String tenant, String... members) {
        String id = Ids.newId().toString();
        UUID t = UUID.fromString(tenant);
        TenantContext.runAs(t, () -> jdbc.sql("""
                insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                .param("id", id).param("t", tenant).param("name", "Группа " + id).update());
        for (String m : members) {
            TenantContext.runAs(t, () -> jdbc.sql("""
                    insert into group_member (tenant_id, group_id, user_id)
                    values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))""")
                    .param("t", tenant).param("g", id).param("u", m).update());
        }
        return id;
    }
}
