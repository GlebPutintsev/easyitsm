package ru.easyitsm.changes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
 * Помощники API-тестов изменений. Каждый тест берёт свои услуги и своё окно в далёком будущем, поэтому
 * конфликты и заморозки соседних тестов на общей базе не мешают.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ChangeApiSupport {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    static final String LOW_RISK = """
            {"users":"none","service_criticality":"low","experience":"routine","rollback":"simple",
             "downtime":"none","testing":"full","dependencies":"none"}""";
    static final String MEDIUM_RISK = """
            {"users":"many","service_criticality":"high","experience":"done_before","rollback":"simple",
             "downtime":"within_window","testing":"partial","dependencies":"none"}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    final String author = Ids.newId().toString();
    final String manager = Ids.newId().toString();
    final String manager2 = Ids.newId().toString();
    final String member = Ids.newId().toString();
    final String service = Ids.newId().toString();

    /** Окно работ: случайный день через 1–20 лет, 02:00–04:00 UTC. */
    final Instant windowStart = Instant.now().truncatedTo(ChronoUnit.DAYS)
            .plus(ThreadLocalRandom.current().nextInt(365, 7300), ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
    final Instant windowEnd = windowStart.plus(2, ChronoUnit.HOURS);

    void users(String... ids) {
        for (String id : ids) {
            TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("""
                    insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                    values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                    on conflict (id) do nothing""")
                    .param("id", id).param("t", DEMO).param("login", "c" + id.replace("-", ""))
                    .param("name", "Тест " + id.substring(30)).update());
        }
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return as(req, user, roles, DEMO);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles, String tenant) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** Изменяющий запрос к изменению с актуальным If-Match (версию берём от имени того же пользователя). */
    MockHttpServletRequestBuilder change(MockHttpServletRequestBuilder req, String id, String user, String roles)
            throws Exception {
        String etag = mvc.perform(as(get("/api/v1/changes/" + id), user, roles)).andReturn().getResponse()
                .getHeader("ETag");
        if (etag == null) {
            etag = mvc.perform(as(get("/api/v1/changes/" + id), manager, "CHG")).andReturn().getResponse()
                    .getHeader("ETag");
        }
        MockHttpServletRequestBuilder r = as(req, user, roles);
        return etag == null ? r : r.header("If-Match", etag);
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    String create(String user, String roles, String json) throws Exception {
        return body(perform(as(post("/api/v1/changes"), user, roles).content(json)).andExpect(status().isCreated()));
    }

    /** Обычное изменение автора с заполненными полями и планом, в черновике. */
    String readyNormal(String title) throws Exception {
        String id = read(create(author, "AGT", """
                {"type":"normal","title":"%s","description":"Обновить сервер приложений","serviceIds":["%s"]}"""
                .formatted(title, service)), "$.id");
        fill(id);
        return id;
    }

    void fill(String id) throws Exception {
        perform(change(patch("/api/v1/changes/" + id), id, author, "AGT").content("""
                {"justification":"Закрыть уязвимость","impact":"Недоступен личный кабинет",
                 "windowStart":"%s","windowEnd":"%s","downtimeMinutes":30,
                 "impactRows":[{"service":"Личный кабинет","description":"Недоступен","duration":"30 минут"}]}"""
                .formatted(windowStart, windowEnd))).andExpect(status().isOk());
        perform(change(put("/api/v1/changes/" + id + "/plan"), id, author, "AGT").content("""
                {"steps":[
                  {"phase":"prepare","title":"Резервная копия","durationMinutes":15},
                  {"phase":"implement","title":"Остановить службу","durationMinutes":5,"interruption":"Да"},
                  {"phase":"implement","title":"Установить релиз 2.4","durationMinutes":30},
                  {"phase":"verify","title":"Проверить вход","durationMinutes":10,"checkCriteria":"Вход за 2 с"},
                  {"phase":"rollback","title":"Вернуть релиз 2.3","durationMinutes":20}]}""")).andExpect(status().isOk());
    }

    ResultActions action(String id, String path, String user, String roles, String json) throws Exception {
        return perform(change(post("/api/v1/changes/" + id + path), id, user, roles).content(json == null ? "{}" : json));
    }

    String fetch(String id, String user, String roles) throws Exception {
        return body(perform(as(get("/api/v1/changes/" + id), user, roles)).andExpect(status().isOk()));
    }

    /** Черновик → оценка → риск → на согласование. */
    String toApproval(String id, String risk) throws Exception {
        action(id, "/submit", author, "AGT", null).andExpect(status().isOk());
        perform(change(put("/api/v1/changes/" + id + "/risk"), id, manager, "CHG")
                .content("{\"answers\":" + risk + "}")).andExpect(status().isOk());
        return body(action(id, "/send-to-approval", manager, "CHG", null).andExpect(status().isOk()));
    }
}
