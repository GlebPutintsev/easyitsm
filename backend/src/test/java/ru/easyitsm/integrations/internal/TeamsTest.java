package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Бот Teams (CHN-04): App ID и арендатор обязательны, приём только с JWT Bot Framework (подпись, аудитория,
 * serviceUrl), привязка кодом, заявка, комментарий по номеру и ответом с цитатой, ответ уходит на serviceUrl с
 * токеном Entra. Ключи JWT, выдача токена и Bot Framework — HTTP-сервер теста на 127.0.0.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String APP_ID = "0b6e2a51-4c1d-4e7a-9a52-6f1d2c3b4a59";
    // Секрет клиента собирается из частей, чтобы не походить на настоящий для gitleaks.
    static final String SECRET = "client-" + "s".repeat(8);
    static final RSAKey KEY;
    static final HttpServer SERVER;
    static final List<String> ACTIVITIES = new CopyOnWriteArrayList<>();
    static final AtomicInteger IDS = new AtomicInteger(1000);

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("k1").generate();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String reply;
                int code = 200;
                if (path.equals("/keys")) {
                    reply = new JWKSet(KEY.toPublicJWK()).toString();
                } else if (path.endsWith("/oauth2/v2.0/token")) {
                    if (body.contains("client_secret=" + SECRET) && body.contains("client_id=" + APP_ID)) {
                        reply = "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"entra-ok\"}";
                    } else {
                        code = 401;
                        reply = "{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: Invalid client secret\"}";
                    }
                } else if (path.startsWith("/v3/conversations/") && path.endsWith("/activities")) {
                    if (!"Bearer entra-ok".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        code = 401;
                        reply = "{\"error\":{\"message\":\"Unauthorized\"}}";
                    } else {
                        int id = IDS.incrementAndGet();
                        ACTIVITIES.add(path + " " + body + " #" + id);
                        reply = "{\"id\":\"" + id + "\"}";
                    }
                } else {
                    code = 404;
                    reply = "{}";
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            SERVER.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @DynamicPropertySource
    static void teams(DynamicPropertyRegistry registry) {
        registry.add("easyitsm.integrations.teams.jwks-uri", () -> base() + "/keys");
        registry.add("easyitsm.integrations.teams.login-url", TeamsTest::base);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MessengerRunner runner;

    final String admin = Ids.newId().toString();
    final String marker = "teams-" + Ids.newId().toString().substring(24);
    final String conv = "a:1" + Ids.newId().toString().replace("-", "") + "x".repeat(80);

    @Test
    void teamsBotVerifiesBotFrameworkAndRepliesThroughServiceUrl() throws Exception {
        allowLocalApi();
        String base = "{\"kind\":\"teams\",\"name\":\"Teams " + marker + "\",\"token\":\"" + SECRET + "\"";
        perform(asAdmin(post("/api/v1/integrations/messengers")).content(base + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_messenger"));
        perform(asAdmin(post("/api/v1/integrations/messengers"))
                .content(base.replace(SECRET, "wrong") + ",\"appId\":\"" + APP_ID + "\",\"appTenant\":\"contoso.onmicrosoft.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("messenger_unreachable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("AADSTS7000215")));
        String created = body(perform(asAdmin(post("/api/v1/integrations/messengers"))
                .content(base + ",\"appId\":\"" + APP_ID + "\",\"appTenant\":\"contoso.onmicrosoft.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appId").value(APP_ID))
                .andExpect(jsonPath("$.botUrl").value("https://teams.microsoft.com/l/chat/0/0?users=28:" + APP_ID)));
        assertThat(created).doesNotContain(SECRET);
        UUID bot = UUID.fromString(JsonPath.read(created, "$.id"));

        // Без JWT, с чужой аудиторией, с неизвестным ботом — 401; чужой serviceUrl — 400.
        String hello = activity("m0", "привет", null);
        perform(post(path(bot)).contentType(MediaType.APPLICATION_JSON).content(hello)).andExpect(status().isUnauthorized());
        perform(inbound(bot, jwt("другое-приложение", base() + "/"), hello)).andExpect(status().isUnauthorized());
        perform(inbound(UUID.randomUUID(), jwt(APP_ID, base() + "/"), hello)).andExpect(status().isUnauthorized());
        String evil = hello.replace(base() + "/", "https://evil.example.com/");
        perform(inbound(bot, jwt(APP_ID, "https://evil.example.com/"), evil)).andExpect(status().isBadRequest());

        // Непривязанный разговор — подсказка уходит на serviceUrl с токеном Entra.
        perform(inbound(bot, jwt(APP_ID, base() + "/"), hello)).andExpect(status().isOk());
        sendAll();
        assertThat(ACTIVITIES.getLast()).startsWith("/v3/conversations/" + java.net.URLEncoder.encode(conv,
                StandardCharsets.UTF_8) + "/activities").contains("Профиль");

        // Привязка кодом из профиля.
        String requester = Ids.newId().toString();
        user(requester);
        String link = body(perform(as(post("/api/v1/integrations/my-messengers/" + bot + "/link"), requester, "REQ"))
                .andExpect(status().isOk()));
        String code = JsonPath.read(link, "$.code");
        assertThat((String) JsonPath.read(link, "$.command")).isEqualTo(code);
        perform(inbound(bot, jwt(APP_ID, base() + "/"), activity("m1", code, null))).andExpect(status().isOk());
        sendAll();
        assertThat(ACTIVITIES.getLast()).contains("Готово", "stop");

        // Сообщение — заявка; ответ с цитатой подтверждения — комментарий; номер в начале — тоже комментарий.
        perform(inbound(bot, jwt(APP_ID, base() + "/"), activity("m2", "<p>Не работает Outlook " + marker + "</p>", null)))
                .andExpect(status().isOk());
        sendAll();
        String number = inDemo(() -> jdbc.sql("select number from ticket where title = :t")
                .param("t", "Не работает Outlook " + marker).query(String.class).single());
        String confirm = ACTIVITIES.stream().filter(a -> a.contains("Создана заявка " + number)).findFirst().orElseThrow();
        String confirmId = confirm.substring(confirm.lastIndexOf('#') + 1);
        perform(inbound(bot, jwt(APP_ID, base() + "/"), activity("m3", "Перезагрузил " + marker, confirmId)))
                .andExpect(status().isOk());
        perform(inbound(bot, jwt(APP_ID, base() + "/"), activity("m4", number + ": Всё работает " + marker, null)))
                .andExpect(status().isOk());
        assertThat(inDemo(() -> jdbc.sql("""
                select count(*) from ticket_activity a join ticket t on t.id = a.ticket_id
                 where t.number = :n and a.body in (:b1, :b2)""")
                .param("n", number).param("b1", "Перезагрузил " + marker).param("b2", "Всё работает " + marker)
                .query(Integer.class).single())).isEqualTo(2);
        // Повтор того же сообщения не создаёт второй комментарий.
        perform(inbound(bot, jwt(APP_ID, base() + "/"), activity("m4", number + ": Всё работает " + marker, null)))
                .andExpect(status().isOk());
        assertThat(inDemo(() -> jdbc.sql("select count(*) from ticket_activity where body = :b")
                .param("b", "Всё работает " + marker).query(Integer.class).single())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ помощники

    static String path(UUID bot) {
        return "/api/v1/integrations/teams/" + bot + "/messages";
    }

    MockHttpServletRequestBuilder inbound(UUID bot, String jwt, String body) {
        return post(path(bot)).header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    static String jwt(String audience, String serviceUrl) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("https://api.botframework.com").audience(audience)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)).notBeforeTime(new Date())
                .claim("serviceUrl", serviceUrl).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    String activity(String id, String text, String quotedId) {
        String attachments = quotedId == null ? "" : ",\"attachments\":[{\"contentType\":\"text/html\",\"content\":"
                + MessengerTest.quote("<blockquote itemscope=\"\" itemtype=\"http://schema.skype.com/Reply\" itemid=\""
                + quotedId + "\">…</blockquote><p>" + text + "</p>") + "}]";
        return "{\"type\":\"message\",\"id\":\"" + id + marker + "\",\"serviceUrl\":\"" + base() + "/\","
                + "\"channelId\":\"msteams\",\"from\":{\"id\":\"29:user\",\"name\":\"Анна\"},"
                + "\"conversation\":{\"conversationType\":\"personal\",\"id\":\"" + conv + "\"},"
                + "\"recipient\":{\"id\":\"28:" + APP_ID + "\"},\"text\":" + MessengerTest.quote(text) + attachments + "}";
    }

    void sendAll() throws Exception {
        List<UUID> due = inDemo(() -> jdbc.sql("""
                select id from integration_messenger_outbox where status = 'pending' and next_attempt_at <= now()
                 order by created_at""").query(UUID.class).list());
        for (UUID id : due) {
            inDemo(() -> runner.send(id));
        }
    }

    void allowLocalApi() throws Exception {
        String current = body(perform(asAdmin(get("/api/v1/integrations/outbound-policy"))).andExpect(status().isOk()));
        List<String> hosts = JsonPath.read(current, "$.allowedHosts");
        if (hosts.contains("127.0.0.1")) {
            return;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(hosts);
        next.add("127.0.0.1");
        List<String> quoted = new ArrayList<>();
        next.forEach(s -> quoted.add("\"" + s + "\""));
        String etag = perform(asAdmin(get("/api/v1/integrations/outbound-policy"))).andReturn().getResponse()
                .getHeader("ETag");
        perform(asAdmin(put("/api/v1/integrations/outbound-policy")).header("If-Match", etag)
                .content("{\"allowedHosts\":[" + String.join(",", quoted) + "]}"))
                .andExpect(status().isOk());
    }

    void user(String id) throws Exception {
        inDemo(() -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", DEMO).param("login", "t" + id.replace("-", ""))
                .param("name", "Тест " + id.substring(30)).update());
    }

    <T> T inDemo(Callable<T> action) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), action);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, admin, "ADM");
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
