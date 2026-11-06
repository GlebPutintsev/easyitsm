package ru.easyitsm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.id.Ids;

/**
 * Проверки по OWASP Top 10 2025 и ASVS для всего API сразу (раздел 9.6 ТЗ, NFR-16). Перебираются все
 * эндпоинты приложения, поэтому новый модуль попадает под проверку без правки теста.
 * <ul>
 *   <li>A01: запрет по умолчанию — без входа ни один эндпоинт, кроме входа, не отдаёт данные;
 *       у каждого эндпоинта модуля есть проверка роли; опасные действия требуют повторного входа (SEC-08);</li>
 *   <li>A02: заголовки безопасности на ответах API (SEC-23);</li>
 *   <li>A05: попытки SQL-инъекции в параметрах поиска не ломают запрос (SEC-22);</li>
 *   <li>A10: ошибки без стека и внутренних имён, с trace_id (SEC-28).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwaspApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    private static final Pattern VAR = Pattern.compile("\\{[^}]+}");
    /** Открыты без входа: экран входа, сброс пароля, OIDC. */
    private static final String PUBLIC_PREFIX = "/api/v1/auth/";

    /**
     * DELETE без повторного входа: личные настройки и связи внутри своей работы. Всё остальное удаление —
     * с {@link RequiresRecentAuth}. Новое исключение добавляется сюда с причиной.
     */
    private static final Set<String> DELETE_WITHOUT_REAUTH = Set.of(
            "/api/v1/catalog/items/{id}/favorite",          // своё избранное
            "/api/v1/ticket-views/{id}",                    // свой сохранённый фильтр
            "/api/v1/response-templates/{id}",              // шаблон ответа
            "/api/v1/tickets/{ref}/links/{linkId}",         // связь заявок, видна в истории заявки
            "/api/v1/tickets/{ref}/watchers/{user}",        // наблюдатель заявки
            "/api/v1/me/sessions/{id}",                     // завершить свою сессию
            "/api/v1/admin/duty-shifts/{id}",               // смена в графике дежурств, ставится без повторного входа
            "/api/v1/attachments/{id}",                     // свой файл в заявке или статье, как свой ответ в ленте
            "/api/v1/assets/{id}/tickets/{ticketId}",       // связь актива с заявкой, остаётся в истории актива
            "/api/v1/licenses/{id}/assignments/{assignmentId}", // освободить лицензию, как вернуть актив на склад
            "/api/v1/notifications/push/subscriptions",     // своя подписка на push в этом браузере
            "/api/v1/notification-settings/templates/{kind}/{locale}", // сброс шаблона к стандартному
            "/api/v1/cmdb/relations/{relationId}",          // связь КЕ закрывается датой и остаётся в истории
            "/api/v1/cab/meetings/{id}/items/{itemId}");    // снять изменение с повестки CAB до решения

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    record Endpoint(HttpMethod method, String pattern, HandlerMethod handler) {
        String url() {
            return VAR.matcher(pattern).replaceAll(UUID.randomUUID().toString());
        }
    }

    List<Endpoint> endpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : e.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    out.add(new Endpoint(HttpMethod.valueOf(m.name()), pattern, e.getValue()));
                }
            }
        }
        assertThat(out).hasSizeGreaterThan(100);
        return out;
    }

    @Test
    void everyEndpointDeniesAnonymous() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            MvcResult r = mvc.perform(request(e.method(), e.url()).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn();
            int status = r.getResponse().getStatus();
            if (status >= 500) {
                leaks.add(e.method() + " " + e.pattern() + " → " + status);
            } else if (!e.pattern().startsWith(PUBLIC_PREFIX) && status < 400) {
                leaks.add(e.method() + " " + e.pattern() + " → " + status + " без входа");
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void everyModuleEndpointChecksRole() {
        // Кроме входа и «моих» эндпоинтов (/me, /people — любой вошедший) каждая точка API проверяет роль
        List<String> missing = endpoints().stream()
                .filter(e -> !e.pattern().startsWith(PUBLIC_PREFIX))
                .filter(e -> !e.pattern().startsWith("/api/v1/me"))
                .filter(e -> !hasAnnotation(e.handler(), PreAuthorize.class))
                .map(e -> e.method() + " " + e.pattern() + " (" + e.handler().getBeanType().getSimpleName() + ")")
                .sorted().toList();
        // Список осознанных исключений: доступ к записи проверяет модуль по участию (ABAC), роль любая
        assertThat(missing).allMatch(m -> m.contains("/api/v1/people") || m.contains("/api/v1/tickets")
                || m.contains("/api/v1/approvals") || m.contains("/api/v1/catalog") || m.contains("/api/v1/knowledge")
                || m.contains("/api/v1/surveys") || m.contains("/api/v1/announcements")
                || m.contains("/api/v1/ticket-views") || m.contains("/api/v1/response-templates")
                || m.contains("/api/v1/workspace") || m.contains("/api/v1/sla/instances")
                // Права файла и выдачи поиска — права записи-владельца (заявки, статьи, услуги)
                || m.contains("/api/v1/attachments") || m.contains("/api/v1/search"), "без @PreAuthorize: %s");
    }

    @Test
    void dangerousActionsRequireRecentAuthentication() {
        List<String> missing = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            boolean reauth = hasAnnotation(e.handler(), RequiresRecentAuth.class);
            boolean delete = e.method() == HttpMethod.DELETE && !DELETE_WITHOUT_REAUTH.contains(e.pattern());
            boolean export = e.pattern().contains("/export") && e.method() != HttpMethod.GET; // GET — журнал выгрузок
            boolean roles = e.pattern().startsWith("/api/v1/role-grants") && e.method() != HttpMethod.GET;
            boolean membership = e.pattern().contains("/members/") && e.method() != HttpMethod.GET;
            if ((delete || export || roles || membership) && !reauth) {
                missing.add(e.method() + " " + e.pattern());
            }
        }
        assertThat(missing).as("SEC-08: нужен @RequiresRecentAuth").isEmpty();
    }

    @Test
    void securityHeaders() throws Exception {
        mvc.perform(as(get("/api/v1/tickets"), "AGT"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void injectionAttemptsAreHarmless() throws Exception {
        for (String payload : List.of("' OR '1'='1", "1; DROP TABLE ticket; --", "%' UNION SELECT 1--",
                "\\\" OR 1=1 --", "${7*7}", "<script>alert(1)</script>")) {
            assertNot5xx(as(get("/api/v1/tickets").param("q", payload), "AGT"));
            assertNot5xx(as(get("/api/v1/knowledge/articles").param("q", payload), "AGT"));
            assertNot5xx(as(get("/api/v1/catalog/items").param("q", payload), "REQ"));
            assertNot5xx(as(get("/api/v1/people").param("q", payload), "REQ"));
            assertNot5xx(as(get("/api/v1/audit/events").param("q", payload), "AUD"));
        }
    }

    @Test
    void errorsDoNotRevealInternals() throws Exception {
        for (MockHttpServletRequestBuilder req : List.of(
                as(post("/api/v1/tickets").content("{\"type\": broken json"), "REQ"),
                as(get("/api/v1/tickets/count").param("groupId", "not-a-uuid"), "AGT"),
                as(get("/api/v1/audit/events").param("from", "вчера"), "AUD"),
                as(get("/api/v1/audit/events").param("cursor", "%%%"), "AUD"),
                as(get("/api/v1/sla/instances").param("ticketId", "1 or 1=1"), "AGT"))) {
            String body = mvc.perform(req)
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.trace_id").exists())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Exception").doesNotContain("at ru.easyitsm")
                    .doesNotContain("org.springframework").doesNotContain("SQL").doesNotContain("postgres");
        }
    }

    private void assertNot5xx(MockHttpServletRequestBuilder req) throws Exception {
        int status = mvc.perform(req).andReturn().getResponse().getStatus();
        assertThat(status).isLessThan(500);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String roles) {
        return req.header("X-Dev-User", Ids.newId().toString()).header("X-Dev-Tenant", DEMO)
                .header("X-Dev-Roles", roles).contentType(MediaType.APPLICATION_JSON);
    }

    private static boolean hasAnnotation(HandlerMethod h, Class<? extends java.lang.annotation.Annotation> type) {
        return h.hasMethodAnnotation(type) || AnnotatedElementUtils.hasAnnotation(h.getBeanType(), type);
    }
}
