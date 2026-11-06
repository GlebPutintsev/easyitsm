package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
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

/**
 * Бот Slack (CHN-04): токен приложения обязателен, соединение Socket Mode открывает сервер, привязка — кодом в
 * личном сообщении, заявка из сообщения, повтор конверта не дублирует заявку, ответ в ветке — комментарий,
 * выключенный бот отключается. Web API и WebSocket Slack — серверы теста на 127.0.0.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlackTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    // Токены собираются из частей, чтобы не походить на настоящие для gitleaks.
    static final String BOT_TOKEN = "xo" + "xb-test-" + "b".repeat(8);
    static final String APP_TOKEN = "xa" + "pp-test-" + "c".repeat(8);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MessengerRunner runner;
    @Autowired
    SlackSockets sockets;

    final String admin = Ids.newId().toString();
    final String marker = "slack-" + Ids.newId().toString().substring(24);

    HttpServer api;
    FakeSocket socket;
    final List<String> posted = new CopyOnWriteArrayList<>();
    final AtomicInteger ts = new AtomicInteger(500);

    @BeforeEach
    void startSlack() throws Exception {
        socket = new FakeSocket();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            if (path.endsWith("/apps.connections.open")) {
                reply = ("Bearer " + APP_TOKEN).equals(auth)
                        ? "{\"ok\":true,\"url\":\"ws://127.0.0.1:" + socket.port() + "/link/?ticket=t1\"}"
                        : "{\"ok\":false,\"error\":\"invalid_auth\"}";
            } else if (!("Bearer " + BOT_TOKEN).equals(auth)) {
                reply = "{\"ok\":false,\"error\":\"invalid_auth\"}";
            } else if (path.endsWith("/auth.test")) {
                reply = "{\"ok\":true,\"url\":\"https://easy.slack.com/\",\"team\":\"Easy\",\"user\":\"easy_slack\","
                        + "\"team_id\":\"T1\",\"user_id\":\"UBOT\",\"bot_id\":\"B1\"}";
            } else if (path.endsWith("/chat.postMessage")) {
                posted.add(body);
                reply = "{\"ok\":true,\"channel\":\"D1\",\"ts\":\"900." + ts.incrementAndGet() + "\"}";
            } else {
                reply = "{\"ok\":false,\"error\":\"unknown_method\"}";
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api.start();
    }

    @AfterEach
    void stopSlack() throws Exception {
        sockets.closeAll();
        api.stop(0);
        socket.close();
    }

    String apiUrl() {
        return "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @Test
    void slackBotOverSocketModeLinksCreatesAndComments() throws Exception {
        allowLocalApi();
        String base = "{\"kind\":\"slack\",\"name\":\"Slack " + marker + "\",\"token\":\"" + BOT_TOKEN
                + "\",\"baseUrl\":\"" + apiUrl() + "\"";
        perform(asAdmin(post("/api/v1/integrations/messengers")).content(base + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Укажите токен приложения"));
        perform(asAdmin(post("/api/v1/integrations/messengers")).content(base + ",\"appToken\":\"xapp-wrong\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("messenger_unreachable"));
        String created = body(perform(asAdmin(post("/api/v1/integrations/messengers"))
                .content(base + ",\"appToken\":\"" + APP_TOKEN + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.botUsername").value("easy_slack"))
                .andExpect(jsonPath("$.botUrl").value("https://easy.slack.com/team/UBOT")));
        assertThat(created).doesNotContain(BOT_TOKEN, APP_TOKEN);
        UUID bot = UUID.fromString(JsonPath.read(created, "$.id"));

        String requester = Ids.newId().toString();
        user(requester);
        String link = body(perform(as(post("/api/v1/integrations/my-messengers/" + bot + "/link"), requester, "REQ"))
                .andExpect(status().isOk()));
        String code = JsonPath.read(link, "$.code");
        assertThat((String) JsonPath.read(link, "$.url")).isEqualTo("https://easy.slack.com/team/UBOT");
        assertThat((String) JsonPath.read(link, "$.command")).isEqualTo(code);

        // Сервер сам открывает соединение; Slack здоровается.
        sockets.supervise();
        eventually(() -> sockets.connected().contains(bot));

        // Код в личном сообщении — привязка.
        socket.event("e1", "D1", "U42", code, "100.1", null);
        eventually(() -> socket.acks().contains("e1") && outbox(bot, "Готово") == 1);
        sendAll();
        assertThat(posted.getLast()).contains("\"channel\":\"D1\"", "\"thread_ts\":\"100.1\"", "stop");

        // Сообщение — заявка; повтор того же конверта заявку не дублирует.
        socket.event("e2", "D1", "U42", "Не работает VPN " + marker, "100.2", null);
        socket.event("e2", "D1", "U42", "Не работает VPN " + marker, "100.2", null);
        eventually(() -> tickets() == 1 && outbox(bot, "Создана заявка") == 1);
        Thread.sleep(300);
        assertThat(tickets()).isEqualTo(1);
        String number = inDemo(() -> jdbc.sql("select number from ticket where title = :t")
                .param("t", "Не работает VPN " + marker).query(String.class).single());
        sendAll();
        assertThat(posted.getLast()).contains(number, "ветке");

        // Ответ в ветке исходного сообщения — комментарий заявителя.
        socket.event("e3", "D1", "U42", "Уже лучше " + marker, "100.3", "100.2");
        eventually(() -> outbox(bot, "Комментарий добавлен в " + number) == 1);
        assertThat(inDemo(() -> jdbc.sql("select count(*) from ticket_activity where body = :b")
                .param("b", "Уже лучше " + marker).query(Integer.class).single())).isEqualTo(1);

        // Бот выключен — соединение закрыто.
        perform(asAdmin(patch("/api/v1/integrations/messengers/" + bot)).header("If-Match", "\"0\"")
                .content("{\"enabled\":false}")).andExpect(status().isOk());
        sockets.supervise();
        assertThat(sockets.connected()).doesNotContain(bot);
    }

    // ------------------------------------------------------------------ помощники

    int tickets() throws Exception {
        return inDemo(() -> jdbc.sql("select count(*) from ticket where title = :t")
                .param("t", "Не работает VPN " + marker).query(Integer.class).single());
    }

    int outbox(UUID bot, String text) throws Exception {
        return inDemo(() -> jdbc.sql("""
                select count(*) from integration_messenger_outbox where messenger_id = :m and text like :t""")
                .param("m", bot).param("t", "%" + text + "%").query(Integer.class).single());
    }

    void sendAll() throws Exception {
        List<UUID> due = inDemo(() -> jdbc.sql("""
                select id from integration_messenger_outbox where status = 'pending' and next_attempt_at <= now()
                 order by created_at""").query(UUID.class).list());
        for (UUID id : due) {
            inDemo(() -> runner.send(id));
        }
    }

    static void eventually(Callable<Boolean> check) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!Boolean.TRUE.equals(check.call())) {
            if (System.currentTimeMillis() > until) {
                throw new AssertionError("условие не выполнилось за 10 секунд");
            }
            Thread.sleep(50);
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
                .param("id", id).param("t", DEMO).param("login", "s" + id.replace("-", ""))
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

    /**
     * Минимальный сервер WebSocket (RFC 6455) для одного клиента: рукопожатие, текстовые кадры сервера без маски,
     * чтение маскированных кадров клиента (подтверждения конвертов).
     */
    static final class FakeSocket implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final List<String> received = new CopyOnWriteArrayList<>();
        private volatile Socket client;

        FakeSocket() throws IOException {
            Thread t = new Thread(this::serve, "fake-slack-socket");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        List<String> acks() {
            return received.stream().map(s -> JsonPath.<String>read(s, "$.envelope_id")).toList();
        }

        void event(String envelope, String channel, String user, String text, String ts, String threadTs)
                throws IOException {
            String thread = threadTs == null ? "" : ",\"thread_ts\":\"" + threadTs + "\"";
            send("{\"type\":\"events_api\",\"envelope_id\":\"" + envelope + "\",\"accepts_response_payload\":false,"
                    + "\"payload\":{\"event\":{\"type\":\"message\",\"channel_type\":\"im\",\"channel\":\"" + channel
                    + "\",\"user\":\"" + user + "\",\"text\":" + MessengerTest.quote(text) + ",\"ts\":\"" + ts + "\""
                    + thread + "}}}");
        }

        private void serve() {
            try {
                while (!server.isClosed()) {
                    Socket s = server.accept();
                    client = s;
                    InputStream in = s.getInputStream();
                    String request = readHeaders(in);
                    String key = request.lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:"))
                            .map(l -> l.substring(l.indexOf(':') + 1).strip()).findFirst().orElseThrow();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    send("{\"type\":\"hello\",\"num_connections\":1}");
                    readFrames(in);
                }
            } catch (Exception e) {
                // Сервер закрыт.
            }
        }

        private static String readHeaders(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                buf.write(b);
                String s = buf.toString(StandardCharsets.US_ASCII);
                if (s.endsWith("\r\n\r\n")) {
                    return s;
                }
            }
            throw new IOException("нет заголовков");
        }

        private void readFrames(InputStream in) throws IOException {
            while (true) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 < 0 || b1 < 0) {
                    return;
                }
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = (in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | in.read();
                    }
                }
                byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : new byte[4];
                byte[] payload = in.readNBytes((int) len);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
                int opcode = b0 & 0x0f;
                if (opcode == 8) {
                    return;
                }
                if (opcode == 1) {
                    received.add(new String(payload, StandardCharsets.UTF_8));
                }
            }
        }

        synchronized void send(String text) throws IOException {
            Socket s = client;
            if (s == null) {
                throw new IOException("клиент не подключён");
            }
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else {
                frame.write(126);
                frame.write(payload.length >> 8);
                frame.write(payload.length & 0xff);
            }
            frame.write(payload);
            s.getOutputStream().write(frame.toByteArray());
            s.getOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
            if (client != null) {
                client.close();
            }
        }
    }
}
