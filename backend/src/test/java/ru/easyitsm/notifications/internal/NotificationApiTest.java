package ru.easyitsm.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Уведомления по событиям заявки (NTF-01), центр уведомлений и его права, шаблоны (NTF-02) и Web Push.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;

    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    final String requester = Ids.newId().toString();
    final String agent = Ids.newId().toString();
    final String admin = Ids.newId().toString();
    final String requesterEmail = "req-" + suffix + "@corp.example";
    final String agentEmail = "agent-" + suffix + "@corp.example";
    String group;

    @BeforeEach
    void setUp() {
        user(requester, requesterEmail, "ru");
        user(agent, agentEmail, "en");
        user(admin, null, "ru");
        group = Ids.newId().toString();
        demo(() -> {
            jdbc.sql("""
                    insert into user_group (id, tenant_id, name, created_at, updated_at, version)
                    values (cast(:id as uuid), cast(:t as uuid), :name, now(), now(), 0)""")
                    .param("id", group).param("t", DEMO).param("name", "Группа " + suffix).update();
            jdbc.sql("insert into group_member (tenant_id, group_id, user_id) values (cast(:t as uuid), cast(:g as uuid), cast(:u as uuid))")
                    .param("t", DEMO).param("g", group).param("u", agent).update();
            return null;
        });
    }

    void user(String id, String email, String locale) {
        demo(() -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, email, display_name, locale, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :email, :name, :locale, now(), now(), 0)""")
                .param("id", id).param("t", DEMO).param("login", "n" + id.replace("-", "")).param("email", email)
                .param("name", "Человек " + id.substring(30)).param("locale", locale).update());
    }

    static <T> T demo(java.util.concurrent.Callable<T> action) {
        try {
            return TenantContext.callAs(UUID.fromString(DEMO), action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    String body(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req).andReturn().getResponse().getContentAsString();
    }

    List<String> outgoing(String recipient, String kind) {
        return demo(() -> jdbc.sql("select body_text from mail_outgoing where recipient = :r and kind = :k")
                .param("r", recipient).param("k", kind).query(String.class).list());
    }

    @Test
    void ticketEventsReachTheRightPeople() throws Exception {
        String created = body(as(post("/api/v1/tickets"), requester, "REQ")
                .content("{\"type\":\"incident\",\"title\":\"Не открывается почта\",\"description\":\"Outlook\"}"));
        String ticket = JsonPath.read(created, "$.id");
        String number = JsonPath.read(created, "$.number");
        // Заявителю — письмо о регистрации, но не запись в центре: он сам создал заявку.
        assertThat(outgoing(requesterEmail, "ticket_created")).hasSize(1).first().asString().contains(number);
        mvc.perform(as(get("/api/v1/notifications/unread-count"), requester, "REQ"))
                .andExpect(jsonPath("$.count").value(0));

        demo(() -> jdbc.sql("update ticket set group_id = cast(:g as uuid) where id = cast(:id as uuid)")
                .param("g", group).param("id", ticket).update());
        String etag = mvc.perform(as(get("/api/v1/tickets/" + ticket), agent, "AGT")).andReturn().getResponse()
                .getHeader("ETag");
        mvc.perform(as(patch("/api/v1/tickets/" + ticket), admin, "AGT,MIM").header("If-Match", etag)
                        .content("{\"assigneeId\":\"" + agent + "\"}"))
                .andExpect(status().isOk());

        // Исполнителю (язык en) — назначение в центре уведомлений и письмом.
        String list = body(as(get("/api/v1/notifications"), agent, "AGT"));
        assertThat((List<String>) JsonPath.read(list, "$.items[*].kind")).contains("ticket_assigned");
        assertThat((String) JsonPath.read(list, "$.items[0].title")).isEqualTo("Ticket " + number + " is assigned to you");
        assertThat((String) JsonPath.read(list, "$.items[0].link")).isEqualTo("/agent/tickets/" + number);
        assertThat(outgoing(agentEmail, "ticket_assigned")).hasSize(1);

        // Публичный ответ агента — заявителю, с текстом ответа в письме.
        mvc.perform(as(post("/api/v1/tickets/" + ticket + "/activity"), agent, "AGT")
                        .content("{\"body\":\"Переустановите профиль Outlook\",\"visibility\":\"public\"}"))
                .andExpect(status().isCreated());
        assertThat(outgoing(requesterEmail, "ticket_reply")).hasSize(1).first().asString()
                .contains("Переустановите профиль Outlook");
        String reqList = body(as(get("/api/v1/notifications").param("unread", "true"), requester, "REQ"));
        assertThat((List<String>) JsonPath.read(reqList, "$.items[*].kind")).containsExactly("ticket_reply");
        assertThat((String) JsonPath.read(reqList, "$.items[0].link")).isEqualTo("/portal/tickets/" + number);
        String reqNotification = JsonPath.read(reqList, "$.items[0].id");

        // Чужую запись не прочитать и не отметить: 404.
        String agentNotification = JsonPath.read(list, "$.items[0].id");
        mvc.perform(as(post("/api/v1/notifications/" + agentNotification + "/read"), requester, "REQ"))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/notifications/" + reqNotification + "/read"), requester, "REQ"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/notifications/unread-count"), requester, "REQ"))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(as(post("/api/v1/notifications/read-all"), agent, "AGT")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/notifications/unread-count"), agent, "AGT"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void templatesAreEditableByAdminWithPreview() throws Exception {
        mvc.perform(as(get("/api/v1/notification-settings/templates"), agent, "AGT")).andExpect(status().isForbidden());
        String list = body(as(get("/api/v1/notification-settings/templates"), admin, "ADM"));
        assertThat((List<String>) JsonPath.read(list, "$[?(@.kind=='ticket_reply')].locale")).contains("ru", "en");

        String preview = body(as(post("/api/v1/notification-settings/templates/ticket_reply/ru/preview"), admin, "ADM")
                .content("{\"subject\":\"Ответ {{ticket.number}}: {{ticket.title}}\",\"body\":\"{{comment.body}}\","
                        + "\"confidential\":true}"));
        assertThat((String) JsonPath.read(preview, "$.subject")).isEqualTo("Ответ INC-000123: Конфиденциальная заявка");
        assertThat((String) JsonPath.read(preview, "$.text")).doesNotContain("VPN");

        var r = mvc.perform(as(put("/api/v1/notification-settings/templates/ticket_created/ru"), admin, "ADM")
                        .content("{\"subject\":\"Принято: {{ticket.number}}\",\"body\":\"Спасибо, {{recipient.name}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true))
                .andReturn();
        String etag = r.getResponse().getHeader("ETag");
        // Повторное изменение без If-Match отклоняется (оптимистичная блокировка).
        mvc.perform(as(put("/api/v1/notification-settings/templates/ticket_created/ru"), admin, "ADM")
                        .content("{\"subject\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(as(put("/api/v1/notification-settings/templates/ticket_created/ru"), admin, "ADM")
                        .header("If-Match", etag).content("{\"subject\":\"Принято: {{ticket.number}}\",\"body\":\"Спасибо\"}"))
                .andExpect(status().isOk());
        try {
            body(as(post("/api/v1/tickets"), requester, "REQ")
                    .content("{\"type\":\"incident\",\"title\":\"Шаблон\",\"description\":\"x\"}"));
            String subject = demo(() -> jdbc.sql("""
                            select subject from mail_outgoing where recipient = :r and kind = 'ticket_created'
                             order by created_at desc limit 1""")
                    .param("r", requesterEmail).query(String.class).single());
            assertThat(subject).startsWith("Принято: INC-");
        } finally {
            mvc.perform(as(delete("/api/v1/notification-settings/templates/ticket_created/ru"), admin, "ADM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.custom").value(false));
        }
        mvc.perform(as(get("/api/v1/notification-settings/templates/unknown/ru"), admin, "ADM"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pushSubscriptionAcceptsOnlyBrowserPushServices() throws Exception {
        String info = body(as(get("/api/v1/notifications/push"), requester, "REQ"));
        assertThat((Boolean) JsonPath.read(info, "$.enabled")).isTrue();
        assertThat(WebPush.unb64(JsonPath.read(info, "$.publicKey"))).hasSize(65);

        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        String p256dh = WebPush.b64(WebPush.raw((ECPublicKey) WebPush.newKeyPair().getPublic()));
        String keys = "\"keys\":{\"p256dh\":\"" + p256dh + "\",\"auth\":\"" + WebPush.b64(auth) + "\"}";
        mvc.perform(as(post("/api/v1/notifications/push/subscriptions"), requester, "REQ")
                        .content("{\"endpoint\":\"https://intranet.corp.example/hook\"," + keys + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_endpoint"));
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + suffix;
        mvc.perform(as(post("/api/v1/notifications/push/subscriptions"), requester, "REQ")
                        .content("{\"endpoint\":\"" + endpoint + "\"," + keys + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/notifications/push"), requester, "REQ"))
                .andExpect(jsonPath("$.subscriptions").value(1));
        mvc.perform(as(delete("/api/v1/notifications/push/subscriptions"), requester, "REQ")
                        .content("{\"endpoint\":\"" + endpoint + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/notifications/push"), requester, "REQ"))
                .andExpect(jsonPath("$.subscriptions").value(0));
    }
}
