package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Бот Telegram (CHN-04, CHN-06): настройка и права, привязка по коду из профиля, заявка из сообщения,
 * комментарий ответом, уведомление в бот по перечню данных, /stop. API Telegram — HTTP-сервер теста на 127.0.0.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessengerTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";
    static final String GOOD = "123456:" + "a".repeat(10);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MessengerRunner runner;
    @Autowired
    TicketQueries tickets;
    @Autowired
    TicketCommands commands;

    final String admin = Ids.newId().toString();
    final String marker = "msg-" + Ids.newId().toString().substring(24);

    HttpServer server;
    final List<String> updates = new CopyOnWriteArrayList<>();
    /** Отправленные сообщения: тело запроса и выданный id. */
    record Sent(String body, int id) {
    }

    final List<Sent> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger nextId = new AtomicInteger(1000);

    @BeforeEach
    void startTelegram() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            int code = 200;
            if (!path.startsWith("/bot" + GOOD + "/")) {
                code = 401;
                reply = "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";
            } else if (path.endsWith("/getMe")) {
                reply = "{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"first_name\":\"Easy\",\"username\":\"easy_test_bot\"}}";
            } else if (path.endsWith("/deleteWebhook")) {
                reply = "{\"ok\":true,\"result\":true}";
            } else if (path.endsWith("/getUpdates")) {
                reply = "{\"ok\":true,\"result\":[" + String.join(",", updates) + "]}";
                updates.clear();
            } else if (path.endsWith("/sendMessage")) {
                int id = nextId.incrementAndGet();
                sent.add(new Sent(body, id));
                reply = "{\"ok\":true,\"result\":{\"message_id\":" + id + "}}";
            } else {
                code = 404;
                reply = "{\"ok\":false,\"description\":\"Not Found\"}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopTelegram() {
        server.stop(0);
    }

    String apiUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ настройка

    @Test
    void onlyAdminConfiguresBotTokenIsCheckedAndHidden() throws Exception {
        allowLocalApi();
        String body = "{\"kind\":\"telegram\",\"name\":\"Бот " + marker + "\",\"token\":\"" + GOOD + "\",\"baseUrl\":\""
                + apiUrl() + "\"}";
        perform(as(post("/api/v1/integrations/messengers"), admin, "AGT").content(body)).andExpect(status().isForbidden());
        perform(as(get("/api/v1/integrations/messengers"), admin, "REQ")).andExpect(status().isForbidden());
        perform(asAdmin(post("/api/v1/integrations/messengers")).content(body.replace(GOOD, "123:bad")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("messenger_unreachable"));
        perform(asAdmin(post("/api/v1/integrations/messengers")).content(body.replace(apiUrl(), "http://203.0.113.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("адрес запрещён")));

        String created = body(perform(asAdmin(post("/api/v1/integrations/messengers")).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.botUsername").value("easy_test_bot"))
                .andExpect(jsonPath("$.dataFields.length()").value(2)));
        String id = JsonPath.read(created, "$.id");
        String list = body(perform(as(get("/api/v1/integrations/messengers"), admin, "AUD")).andExpect(status().isOk()));
        assertThat(list).contains(id).doesNotContain(GOOD);
        perform(inOther(get("/api/v1/integrations/messengers/" + id))).andExpect(status().isNotFound());

        perform(asAdmin(patch("/api/v1/integrations/messengers/" + id)).header("If-Match", "\"0\"")
                .content("{\"dataFields\":[\"secret\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_messenger"));
        perform(asAdmin(post("/api/v1/integrations/messengers/" + id + "/check"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        perform(asAdmin(delete("/api/v1/integrations/messengers/" + id))).andExpect(status().isNoContent());
        perform(asAdmin(get("/api/v1/admin/config-versions").param("entity", MessengerService.HISTORY_ENTITY)
                .param("entityId", id)))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // ------------------------------------------------------------------ разговор с ботом

    @Test
    void userLinksBotCreatesTicketCommentsAndGetsNotifications() throws Exception {
        allowLocalApi();
        UUID bot = createBot();
        String requester = Ids.newId().toString();
        user(requester);

        // Профиль: бот в списке, ссылка с одноразовым кодом.
        perform(as(get("/api/v1/integrations/my-messengers"), requester, "REQ")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bot + "')].linked").value(false));
        String link = body(perform(as(post("/api/v1/integrations/my-messengers/" + bot + "/link"), requester, "REQ"))
                .andExpect(status().isOk()));
        String code = JsonPath.read(link, "$.code");
        assertThat((String) JsonPath.read(link, "$.url")).isEqualTo("https://t.me/easy_test_bot?start=" + code);

        // Чужой чат без привязки — подсказка, без заявки.
        update(1, 777, 1, "Помогите", null);
        poll(bot);
        assertThat(lastSent()).contains("\"chat_id\":\"777\"", "Профиль");

        // Привязка по коду; повторно код не работает.
        update(2, 555, 2, "/start " + code, null);
        poll(bot);
        assertThat(lastSent()).contains("\"chat_id\":\"555\"", "Готово");
        perform(as(get("/api/v1/integrations/my-messengers"), requester, "REQ"))
                .andExpect(jsonPath("$[?(@.id == '" + bot + "')].linked").value(true));
        update(3, 556, 3, "/start " + code, null);
        poll(bot);
        assertThat(lastSent()).contains("\"chat_id\":\"556\"", "устарела");

        // Сообщение — новая заявка от пользователя, канал chat.
        update(4, 555, 4, "Не работает принтер " + marker + "\nНа третьем этаже", null);
        poll(bot);
        Sent confirm = sentWith("Создана заявка");
        int confirmId = confirm.id();
        assertThat(confirm.body()).contains("Создана заявка", "Не работает принтер " + marker, "/portal/tickets/");
        TicketSnapshot t = inDemo(() -> jdbc.sql("select id from ticket where title = :t")
                .param("t", "Не работает принтер " + marker).query(UUID.class).optional()
                .flatMap(tickets::find)).orElseThrow();
        assertThat(t.channel()).isEqualTo(Channel.CHAT);
        assertThat(t.requesterId()).isEqualTo(UUID.fromString(requester));

        // Ответ на подтверждение — публичный комментарий заявителя.
        update(5, 555, 5, "Уже лучше", confirmId);
        poll(bot);
        assertThat(lastSent()).contains("Комментарий добавлен в " + t.number());
        assertThat(inDemo(() -> jdbc.sql("""
                select count(*) from ticket_activity where ticket_id = :t and body = 'Уже лучше'""")
                .param("t", t.id()).query(Integer.class).single())).isEqualTo(1);

        // Ответ агента — уведомление заявителю в бот: тема и ссылка есть, текст ответа не передаётся.
        String agent = Ids.newId().toString();
        user(agent);
        inDemo(() -> commands.addComment(t.id(), UUID.fromString(agent), "Секретный ответ " + marker, Visibility.PUBLIC));
        String notice = inDemo(() -> jdbc.sql("""
                select text from integration_messenger_outbox
                 where messenger_id = :m and chat_id = '555' and ticket_id = :t and status = 'pending'""")
                .param("m", bot).param("t", t.id()).query(String.class).list()).getLast();
        assertThat(notice).contains(t.number(), "/portal/tickets/" + t.number()).doesNotContain("Секретный ответ");
        sendAll();

        // /stop — привязка снята.
        update(6, 555, 6, "/stop", null);
        poll(bot);
        assertThat(lastSent()).contains("Бот отключён");
        perform(as(get("/api/v1/integrations/my-messengers"), requester, "REQ"))
                .andExpect(jsonPath("$[?(@.id == '" + bot + "')].linked").value(false));
    }

    @Test
    void botWithoutTicketsAndFailedSendIsRetried() throws Exception {
        allowLocalApi();
        UUID bot = createBot();
        String id = bot.toString();
        perform(asAdmin(patch("/api/v1/integrations/messengers/" + id)).header("If-Match", "\"0\"")
                .content("{\"allowTickets\":false,\"dataFields\":[\"link\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataFields[0]").value("link"));
        String requester = Ids.newId().toString();
        user(requester);
        String code = JsonPath.read(body(perform(as(post("/api/v1/integrations/my-messengers/" + bot + "/link"),
                requester, "REQ"))), "$.code");
        update(1, 901, 1, "/start " + code, null);
        poll(bot);
        update(2, 901, 2, "Хочу заявку " + marker, null);
        poll(bot);
        assertThat(lastSent()).contains("не принимаются");
        assertThat(inDemo(() -> jdbc.sql("select count(*) from ticket where title like :t")
                .param("t", "%" + marker + "%").query(Integer.class).single())).isZero();

        // API недоступен — сообщение остаётся в очереди с ошибкой и следующей попыткой.
        inDemo(() -> jdbc.sql("""
                insert into integration_messenger_outbox (id, tenant_id, messenger_id, chat_id, text, status, attempts,
                    next_attempt_at, created_at, updated_at, version)
                values (:id, cast(:t as uuid), :m, '901', 'x', 'pending', 0, now(), now(), now(), 0)""")
                .param("id", Ids.newId()).param("t", DEMO).param("m", bot).update());
        server.stop(0);
        sendAll();
        Map<String, Object> row = inDemo(() -> jdbc.sql("""
                select attempts, last_error, next_attempt_at > now() as later from integration_messenger_outbox
                 where messenger_id = :m and text = 'x'""").param("m", bot).query().singleRow());
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).startsWith("нет соединения");
        assertThat((Boolean) row.get("later")).isTrue();
        startTelegram();
    }

    // ------------------------------------------------------------------ помощники

    UUID createBot() throws Exception {
        String created = body(perform(asAdmin(post("/api/v1/integrations/messengers"))
                .content("{\"kind\":\"telegram\",\"name\":\"Бот " + marker + "\",\"token\":\"" + GOOD
                        + "\",\"baseUrl\":\"" + apiUrl() + "\"}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    void update(int seq, long chat, int messageId, String text, Integer replyTo) {
        String reply = replyTo == null ? "" : ",\"reply_to_message\":{\"message_id\":" + replyTo + "}";
        updates.add("{\"update_id\":" + (System.nanoTime() % 1_000_000_000L + seq) + ",\"message\":{\"message_id\":"
                + messageId + ",\"chat\":{\"id\":" + chat + ",\"type\":\"private\"},\"from\":{\"id\":" + chat
                + ",\"first_name\":\"Тест\"},\"text\":" + quote(text) + reply + "}}");
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    void poll(UUID bot) throws Exception {
        inDemo(() -> runner.poll(bot));
        sendAll();
    }

    void sendAll() throws Exception {
        List<UUID> due = inDemo(() -> jdbc.sql("""
                select id from integration_messenger_outbox where status = 'pending' and next_attempt_at <= now()
                 order by created_at""").query(UUID.class).list());
        for (UUID id : due) {
            inDemo(() -> runner.send(id));
        }
    }

    String lastSent() {
        return sent.isEmpty() ? "" : sent.getLast().body();
    }

    Sent sentWith(String text) {
        return sent.stream().filter(x -> x.body().contains(text)).reduce((a, b) -> b).orElseThrow();
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
                .param("id", id).param("t", DEMO).param("login", "m" + id.replace("-", ""))
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

    MockHttpServletRequestBuilder inOther(MockHttpServletRequestBuilder req) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "ADM")
                .contentType(MediaType.APPLICATION_JSON);
    }

    ResultActions perform(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req);
    }

    static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }
}
