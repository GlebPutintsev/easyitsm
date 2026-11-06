package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
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
import ru.easyitsm.integrations.api.Webhooks;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Исходящие вебхуки: права, список разрешённых адресов, подпись HMAC, очередь с повторами и ручным повтором,
 * действие «вебхук» в правилах автоматизации (AUT-05). Получатель — HTTP-сервер теста на 127.0.0.1
 * (в профиле test адрес сервера разрешён настройкой allow-loopback).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookDeliveryTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebhookService service;
    @Autowired
    Webhooks webhooks;
    @Autowired
    TicketCommands commands;

    final String admin = Ids.newId().toString();
    final String marker = "wh-" + Ids.newId().toString().substring(24);

    record Received(Map<String, String> headers, String body) {
    }

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger reply = new AtomicInteger(200);

    @BeforeEach
    void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
            received.add(new Received(headers, new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)));
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.get(), ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    // ------------------------------------------------------------------ права и список адресов

    @Test
    void onlyAdminManagesWebhooksAuditorReadsOtherTenantDoesNotSee() throws Exception {
        String body = "{\"name\":\"Права " + marker + "\",\"url\":\"" + hookUrl() + "\"}";
        perform(as(post("/api/v1/integrations/webhooks"), "AGT").content(body)).andExpect(status().isForbidden());
        perform(as(post("/api/v1/integrations/webhooks"), "AUD").content(body)).andExpect(status().isForbidden());
        perform(as(get("/api/v1/integrations/webhooks"), "REQ")).andExpect(status().isForbidden());
        perform(as(put("/api/v1/integrations/outbound-policy"), "AUD").content("{\"allowedHosts\":[]}"))
                .andExpect(status().isForbidden());

        Hook h = createHook("Права " + marker);
        assertThat(h.secret).startsWith("whsec_");
        String list = body(perform(as(get("/api/v1/integrations/webhooks"), "AUD")).andExpect(status().isOk()));
        assertThat(list).contains(h.id.toString()).doesNotContain(h.secret);
        perform(inOther(get("/api/v1/integrations/webhooks/" + h.id))).andExpect(status().isNotFound());

        perform(asAdmin(post("/api/v1/integrations/webhooks")).content("{\"name\":\"x\",\"url\":\"ftp://a.ru/x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_webhook"));
        perform(asAdmin(put("/api/v1/integrations/outbound-policy")).header("If-Match", policyEtag())
                .content("{\"allowedHosts\":[\"*\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_policy"));
        perform(asAdmin(put("/api/v1/integrations/outbound-policy")).content("{\"allowedHosts\":[]}"))
                .andExpect(status().is(428));

        perform(asAdmin(delete("/api/v1/integrations/webhooks/" + h.id))).andExpect(status().isNoContent());
        perform(asAdmin(get("/api/v1/integrations/webhooks/" + h.id))).andExpect(status().isNotFound());
    }

    @Test
    void addressOutsideAllowListIsNotCalled() throws Exception {
        allowLocalReceiver();
        String created = body(perform(asAdmin(post("/api/v1/integrations/webhooks"))
                .content("{\"name\":\"Чужой " + marker + "\",\"url\":\"http://203.0.113.9/hook\"}"))
                .andExpect(status().isCreated()));
        String id = JsonPath.read(created, "$.webhook.id");
        perform(asAdmin(post("/api/v1/integrations/webhooks/" + id + "/test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.startsWith("адрес запрещён")));
    }

    // ------------------------------------------------------------------ подпись и тест

    @Test
    void testEventIsSignedRotationChangesKey() throws Exception {
        allowLocalReceiver();
        Hook h = createHook("Подпись " + marker);
        perform(asAdmin(post("/api/v1/integrations/webhooks/" + h.id + "/test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.status").value(200));
        Received r = received.getLast();
        assertThat(r.headers.get("x-easyitsm-event")).isEqualTo("webhook.test");
        assertThat(signatureValid(r, h.secret)).isTrue();
        assertThat((String) JsonPath.read(r.body, "$.type")).isEqualTo("webhook.test");
        assertThat((Integer) JsonPath.read(r.body, "$.schema_version")).isEqualTo(1);
        assertThat((String) JsonPath.read(r.body, "$.tenant")).isEqualTo(DEMO);

        String rotated = JsonPath.read(body(perform(asAdmin(post("/api/v1/integrations/webhooks/" + h.id + "/secret")))
                .andExpect(status().isOk())), "$.secret");
        assertThat(rotated).isNotEqualTo(h.secret);
        perform(asAdmin(post("/api/v1/integrations/webhooks/" + h.id + "/test"))).andExpect(jsonPath("$.ok").value(true));
        assertThat(signatureValid(received.getLast(), h.secret)).isFalse();
        assertThat(signatureValid(received.getLast(), rotated)).isTrue();
    }

    // ------------------------------------------------------------------ очередь

    @Test
    void failedDeliveryIsRetriedThenDeadThenRetriedByHand() throws Exception {
        allowLocalReceiver();
        Hook h = createHook("Повторы " + marker);
        reply.set(500);
        UUID delivery = inDemo(() -> webhooks.enqueue(h.id, "automation.rule", Map.of("rule", marker), "test", null));
        assertThat(inDemo(() -> service.deliver(delivery))).isTrue();
        Map<String, Object> row = row(delivery);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_status")).isEqualTo(500);
        assertThat((Boolean) row.get("later")).isTrue();
        // Срок следующей попытки не наступил: обработчик её не берёт.
        assertThat(inDemo(() -> service.deliver(delivery))).isFalse();

        // Последняя попытка неудачна — доставка «dead».
        inDemo(() -> jdbc.sql("update integration_webhook_delivery set attempts = 8, next_attempt_at = now() where id = :id")
                .param("id", delivery).update());
        assertThat(inDemo(() -> service.deliver(delivery))).isTrue();
        assertThat(row(delivery).get("status")).isEqualTo("dead");
        perform(as(get("/api/v1/integrations/webhook-deliveries?status=dead&webhookId=" + h.id), "AUD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(delivery.toString()))
                .andExpect(jsonPath("$.items[0].attempts").value(9));
        assertThat(received).hasSize(2);

        // Ручной повтор: попытки сначала, тело то же самое.
        reply.set(204);
        perform(asAdmin(post("/api/v1/integrations/webhook-deliveries/" + delivery + "/retry"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending")).andExpect(jsonPath("$.attempts").value(0));
        assertThat(inDemo(() -> service.deliver(delivery))).isTrue();
        assertThat(row(delivery).get("status")).isEqualTo("delivered");
        assertThat(received.getLast().body).isEqualTo(received.getFirst().body);
        assertThat(received.getLast().headers.get("x-easyitsm-delivery")).isEqualTo(delivery.toString());
        perform(asAdmin(post("/api/v1/integrations/webhook-deliveries/" + delivery + "/retry")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_failed"));
        perform(inOther(get("/api/v1/integrations/webhook-deliveries/" + delivery))).andExpect(status().isNotFound());
    }

    @Test
    void disabledWebhookCannotBeQueued() throws Exception {
        allowLocalReceiver();
        Hook h = createHook("Выкл " + marker);
        perform(asAdmin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                "/api/v1/integrations/webhooks/" + h.id)).header("If-Match", "\"0\"").content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(inDemo(() -> webhooks.find(h.id)).orElseThrow().enabled()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inDemo(() -> webhooks.enqueue(h.id, "x", Map.of(),
                        "test", null)))
                .hasMessageContaining("выключен");
    }

    // ------------------------------------------------------------------ действие в автоматизации

    @Test
    void automationRuleQueuesSignedTicketEvent() throws Exception {
        allowLocalReceiver();
        Hook h = createHook("Правило " + marker);
        perform(asAdmin(post("/api/v1/automation/rules")).content("{\"name\":\"Вебхук " + marker
                + "\",\"trigger\":\"ticket_created\",\"conditions\":{\"match\":\"all\",\"items\":[{\"field\":\"title\","
                + "\"op\":\"contains\",\"value\":\"" + marker + "\"}]},\"actions\":[{\"type\":\"webhook\",\"webhookId\":\""
                + h.id + "\"}],\"enabled\":true}")).andExpect(status().isCreated());
        // Выключенный или чужой вебхук в правило не попадает.
        perform(asAdmin(post("/api/v1/automation/rules")).content("{\"name\":\"Нет " + marker
                + "\",\"trigger\":\"ticket_created\",\"conditions\":{\"match\":\"all\",\"items\":[]},"
                + "\"actions\":[{\"type\":\"webhook\",\"webhookId\":\"" + Ids.newId() + "\"}],\"enabled\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("вебхук не найден")));

        TicketRef ref = inDemo(() -> commands.create(NewTicket.of(TicketType.INCIDENT, "Упал сервис " + marker,
                "Описание не уходит наружу", Ids.newId(), Channel.PORTAL)));
        UUID delivery = inDemo(() -> jdbc.sql("""
                select id from integration_webhook_delivery where webhook_id = :w and ticket_number = :n""")
                .param("w", h.id).param("n", ref.number()).query(UUID.class).single());
        assertThat(inDemo(() -> service.deliver(delivery))).isTrue();
        Received r = received.getLast();
        assertThat(signatureValid(r, h.secret)).isTrue();
        assertThat(r.headers.get("x-easyitsm-event")).isEqualTo("automation.rule");
        assertThat((String) JsonPath.read(r.body, "$.data.rule")).isEqualTo("Вебхук " + marker);
        assertThat((String) JsonPath.read(r.body, "$.data.trigger")).isEqualTo("ticket_created");
        assertThat((String) JsonPath.read(r.body, "$.data.ticket.number")).isEqualTo(ref.number());
        assertThat((String) JsonPath.read(r.body, "$.data.ticket.title")).isEqualTo("Упал сервис " + marker);
        assertThat(r.body).doesNotContain("Описание не уходит наружу");

        String runs = body(perform(asAdmin(get("/api/v1/automation/runs?ticket=" + ref.number()))));
        assertThat((String) JsonPath.read(runs, "$.items[0].results[0].detail")).isEqualTo("queued");
        perform(as(get("/api/v1/integrations/webhook-deliveries/" + delivery), "AUD")).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.status").value("delivered"))
                .andExpect(jsonPath("$.delivery.source").value("Вебхук " + marker));
    }

    // ------------------------------------------------------------------ помощники

    record Hook(UUID id, String secret) {
    }

    Hook createHook(String name) throws Exception {
        String created = body(perform(asAdmin(post("/api/v1/integrations/webhooks"))
                .content("{\"name\":\"" + name + "\",\"url\":\"" + hookUrl() + "\"}"))
                .andExpect(status().isCreated()));
        return new Hook(UUID.fromString(JsonPath.read(created, "$.webhook.id")), JsonPath.read(created, "$.secret"));
    }

    /** Добавляет 127.0.0.1 в список арендатора: тесты класса делят его, поэтому список не перезаписывается. */
    void allowLocalReceiver() throws Exception {
        String current = body(perform(asAdmin(get("/api/v1/integrations/outbound-policy"))).andExpect(status().isOk()));
        List<String> hosts = JsonPath.read(current, "$.allowedHosts");
        if (hosts.contains("127.0.0.1")) {
            return;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(hosts);
        next.add("127.0.0.1");
        List<String> quoted = new ArrayList<>();
        next.forEach(s -> quoted.add("\"" + s + "\""));
        perform(asAdmin(put("/api/v1/integrations/outbound-policy")).header("If-Match", policyEtag())
                .content("{\"allowedHosts\":[" + String.join(",", quoted) + "]}"))
                .andExpect(status().isOk());
    }

    String policyEtag() throws Exception {
        return perform(asAdmin(get("/api/v1/integrations/outbound-policy"))).andReturn().getResponse().getHeader("ETag");
    }

    static boolean signatureValid(Received r, String key) {
        String expected = "sha256=" + HexFormat.of().formatHex(WebhookSecrets.hmac(key,
                (r.headers.get("x-easyitsm-timestamp") + "." + r.body).getBytes(StandardCharsets.UTF_8)));
        return expected.equals(r.headers.get("x-easyitsm-signature"));
    }

    Map<String, Object> row(UUID delivery) throws Exception {
        return inDemo(() -> jdbc.sql("""
                select status, attempts, last_status, next_attempt_at > now() + interval '30 seconds' as later
                  from integration_webhook_delivery where id = :id""").param("id", delivery).query().singleRow());
    }

    <T> T inDemo(Callable<T> action) throws Exception {
        return TenantContext.callAs(UUID.fromString(DEMO), action);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String roles) {
        return req.header("X-Dev-User", admin).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, "ADM");
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
