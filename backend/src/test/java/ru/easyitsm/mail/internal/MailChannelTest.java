package ru.easyitsm.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Канал почты целиком (CHN-01, CHN-02, поток 6.4) на настоящем PostgreSQL и почтовом сервере в памяти:
 * письмо заявителю с токеном, заявка из письма IMAP, ответ письмом в ленту, автоответы, неизвестные
 * отправители, подделанный токен, DMARC с отклонением и карантином, права и изоляция арендаторов.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MailChannelTest.Dns.class)
class MailChannelTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    @TestConfiguration
    static class Dns {
        static final FakeDns DNS = new FakeDns()
                .txt("_dmarc.reject.example", "v=DMARC1; p=reject")
                .txt("_dmarc.quarantine.example", "v=DMARC1; p=quarantine");

        @Bean
        @Primary
        DnsResolver fakeDns() {
            return DNS;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MailDelivery delivery;
    @Autowired
    MailPolling polling;
    @Autowired
    MailSettings settings;
    @Autowired
    ReplyTokens tokens;

    final String suffix = UUID.randomUUID().toString().substring(0, 8);
    final String admin = Ids.newId().toString();
    final String requester = Ids.newId().toString();
    final String stranger = Ids.newId().toString();
    final String requesterEmail = "anna-" + suffix + "@corp.example";
    final String strangerEmail = "oleg-" + suffix + "@corp.example";
    final String boxAddress = "support-" + suffix + "@itsm.example";
    String mailboxId;
    GreenMailUser boxUser;

    @BeforeEach
    void setUp() throws Exception {
        user(DEMO, admin, "admin-" + suffix + "@corp.example");
        user(DEMO, requester, requesterEmail);
        user(DEMO, stranger, strangerEmail);
        boxUser = greenMail.setUser(boxAddress, "box-" + suffix, "box-pass");

        String etag = mvc.perform(asAdmin(get("/api/v1/mail-settings/transport"))).andReturn().getResponse()
                .getHeader("ETag");
        mvc.perform(asAdmin(put("/api/v1/mail-settings/transport")).header("If-Match", etag == null ? "" : etag)
                        .content("""
                                {"protocol":"smtp","host":"localhost","port":3025,"security":"none",
                                 "fromAddress":"%s","fromName":"Служба поддержки"}""".formatted(boxAddress)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretSet").value(false));

        String json = mvc.perform(asAdmin(post("/api/v1/mail-settings/mailboxes")).content("""
                        {"address":"%s","protocol":"imap","host":"localhost","port":3143,"security":"none",
                         "username":"box-%s","secret":"box-pass","authPolicy":"enforce"}""".formatted(boxAddress, suffix)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secretSet").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("box-pass");
        mailboxId = JsonPath.read(json, "$.id");
    }

    void user(String tenant, String id, String email) {
        TenantContext.runAs(UUID.fromString(tenant), () -> jdbc.sql("""
                insert into user_account (id, tenant_id, login, email, display_name, created_at, updated_at, version)
                values (cast(:id as uuid), cast(:t as uuid), :login, :email, :name, now(), now(), 0)
                on conflict (id) do nothing""")
                .param("id", id).param("t", tenant).param("login", "u" + id.replace("-", ""))
                .param("email", email).param("name", "Пользователь " + id.substring(30)).update());
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String tenant, String user, String roles) {
        return req.header("X-Dev-User", user).header("X-Dev-Tenant", tenant).header("X-Dev-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON);
    }

    MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder req) {
        return as(req, DEMO, admin, "ADM");
    }

    void receive(String raw) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        boxUser.deliver(msg);
        TenantContext.runAs(UUID.fromString(DEMO), () -> polling.poll(settings.mailbox(UUID.fromString(mailboxId))
                .orElseThrow()));
    }

    record Logged(String verdict, UUID ticketId, String detail) {
    }

    Logged logged(String messageId) {
        return inDemo(() -> jdbc.sql(
                        "select verdict, ticket_id, detail from mail_inbound where message_id = :m")
                .param("m", messageId)
                .query((rs, n) -> new Logged(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3)))
                .single());
    }

    String mail(String from, String messageId, String subject, String extraHeaders, String body) {
        return "From: " + from + "\nTo: " + boxAddress + "\nSubject: " + subject + "\nMessage-ID: " + messageId
                + "\nDate: Fri, 06 Nov 2026 08:00:00 +0300\nMIME-Version: 1.0\n"
                + "Content-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\n" + extraHeaders
                + "\n" + body + "\n";
    }

    @Test
    void emailCreatesTicketAndReplyGoesToItsFeed() throws Exception {
        String id1 = "<new-" + suffix + "@corp.example>";
        receive(mail("Анна <" + requesterEmail + ">", id1, "Не печатает принтер", "",
                "Принтер на 3 этаже не печатает.\n\n-- \nАнна"));
        Logged created = logged(id1);
        assertThat(created.verdict()).isEqualTo("ticket_created");
        UUID ticketId = created.ticketId();
        var ticket = inDemo(() -> jdbc.sql(
                        "select number, title, channel, requester_id::text from ticket where id = :id")
                .param("id", ticketId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)}).single());
        assertThat(ticket[1]).isEqualTo("Не печатает принтер");
        assertThat(ticket[2]).isEqualTo("email");
        assertThat(ticket[3]).isEqualTo(requester);

        // Повторно забранное письмо (тот же Message-ID) второй заявки не создаёт.
        receive(mail(requesterEmail, id1, "Не печатает принтер", "", "Повтор"));
        assertThat(count("select count(*) from ticket where requester_id = cast(:u as uuid)", requester)).isEqualTo(1);

        // Ответ заявителя на наше письмо: In-Reply-To с токеном → публичный ответ в ленте.
        String ourMessageId = tokens.messageId(UUID.fromString(DEMO), ticketId, UUID.randomUUID(), "itsm.example");
        String id2 = "<reply-" + suffix + "@corp.example>";
        receive(mail(requesterEmail, id2, "Re: Не печатает принтер [" + ticket[0] + "]", "In-Reply-To: " + ourMessageId
                + "\n", "Уже заработало, спасибо!\n\n" + MailText.REPLY_MARKER + "\n\nстарый текст"));
        Logged reply = logged(id2);
        assertThat(reply.verdict()).isEqualTo("comment_added");
        assertThat(reply.ticketId()).isEqualTo(ticketId);
        String body = inDemo(() -> jdbc.sql("""
                        select body from ticket_activity where ticket_id = :t and kind = 'reply'
                         order by created_at desc limit 1""")
                .param("t", ticketId).query(String.class).single());
        assertThat(body).isEqualTo("Уже заработало, спасибо!");

        // Чужой человек с верным токеном в заявку не пишет: у него своя новая заявка.
        String id3 = "<stranger-" + suffix + "@corp.example>";
        receive(mail(strangerEmail, id3, "Re: Не печатает", "References: " + ourMessageId + "\n", "Я тоже!"));
        Logged strangerMail = logged(id3);
        assertThat(strangerMail.verdict()).isEqualTo("ticket_created");
        assertThat(strangerMail.ticketId()).isNotEqualTo(ticketId);

        // Подделанный токен не связывает письмо с заявкой.
        String forged = ourMessageId.replace(tokens.token(UUID.fromString(DEMO), ticketId), "a".repeat(20));
        String id4 = "<forged-" + suffix + "@corp.example>";
        receive(mail(requesterEmail, id4, "Re: x [" + ticket[0] + "]", "In-Reply-To: " + forged + "\n", "Текст"));
        assertThat(logged(id4).ticketId()).isNotEqualTo(ticketId);
    }

    @Test
    void attachmentsOfEmailGoToTicketFiles() throws Exception {
        String id = "<files-" + suffix + "@corp.example>";
        String b64 = java.util.Base64.getMimeEncoder().encodeToString("журнал ошибок".getBytes(StandardCharsets.UTF_8));
        String raw = "From: " + requesterEmail + "\nTo: " + boxAddress + "\nSubject: Ошибка в 1С\nMessage-ID: " + id
                + "\nDate: Fri, 06 Nov 2026 08:00:00 +0300\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"b1\"\n\n"
                + "--b1\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: 8bit\n\nЛог во вложении.\n"
                + "--b1\nContent-Type: text/plain; charset=UTF-8; name=\"=?UTF-8?B?"
                + java.util.Base64.getEncoder().encodeToString("лог.txt".getBytes(StandardCharsets.UTF_8))
                + "?=\"\nContent-Disposition: attachment\nContent-Transfer-Encoding: base64\n\n" + b64 + "\n--b1--\n";
        receive(raw);
        Logged created = logged(id);
        assertThat(created.verdict()).isEqualTo("ticket_created");
        var files = inDemo(() -> jdbc.sql("""
                        select file_name, status, visibility, created_by::text from attachment
                         where owner_type = 'ticket' and owner_id = :t""")
                .param("t", created.ticketId())
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .list());
        if (System.getenv("EASYITSM_TEST_S3_ENDPOINT") != null) {
            // Файл в карантине на проверке; скачать его можно будет после антивируса (AttachmentApiTest).
            assertThat(files).hasSize(1);
            assertThat(files.getFirst()).containsExactly("лог.txt", "scanning", "public", requester);
            assertThat(created.detail()).doesNotContain("вложения");
        } else {
            // Без хранилища заявка всё равно создаётся, а журнал письма говорит, что файлы не сохранены.
            assertThat(files).isEmpty();
            assertThat(created.detail()).contains("вложения не сохранены (1)");
        }
    }

    @Test
    void autoRepliesUnknownSendersAndDmarcAreFiltered() throws Exception {
        String auto = "<auto-" + suffix + "@corp.example>";
        receive(mail(requesterEmail, auto, "Нет на месте", "Auto-Submitted: auto-replied\n", "Я в отпуске"));
        assertThat(logged(auto).verdict()).isEqualTo("auto_reply");
        assertThat(logged(auto).ticketId()).isNull();

        String unknown = "<unknown-" + suffix + "@corp.example>";
        receive(mail("nobody-" + suffix + "@corp.example", unknown, "Помогите", "", "текст"));
        assertThat(logged(unknown).verdict()).isEqualTo("unknown_sender");

        // Отправитель в домене с DMARC p=reject без подписи — письмо отклоняется.
        String rejectEmail = "spoof-" + suffix + "@reject.example";
        user(DEMO, Ids.newId().toString(), rejectEmail);
        String rejected = "<rej-" + suffix + "@reject.example>";
        receive(mail(rejectEmail, rejected, "Срочно оплатите", "", "текст"));
        assertThat(logged(rejected).verdict()).isEqualTo("auth_failed");
        assertThat(logged(rejected).detail()).contains("dmarc=fail");

        // p=quarantine — в карантин; администратор принимает письмо, и появляется заявка.
        String quarantineEmail = "q-" + suffix + "@quarantine.example";
        user(DEMO, Ids.newId().toString(), quarantineEmail);
        String q = "<q-" + suffix + "@quarantine.example>";
        receive(mail(quarantineEmail, q, "Проверьте меня", "", "текст"));
        assertThat(logged(q).verdict()).isEqualTo("quarantined");
        String inboundId = inDemo(() -> jdbc.sql(
                "select id::text from mail_inbound where message_id = :m").param("m", q).query(String.class).single());
        mvc.perform(as(post("/api/v1/mail-settings/inbound/" + inboundId + "/release"), DEMO, admin, "AGT"))
                .andExpect(status().isForbidden());
        mvc.perform(asAdmin(post("/api/v1/mail-settings/inbound/" + inboundId + "/release")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("released"));
        assertThat(logged(q).ticketId()).isNotNull();
        mvc.perform(asAdmin(get("/api/v1/mail-settings/inbound").param("verdict", "auth_failed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].verdict").value("auth_failed"));
    }

    @Test
    void notificationEmailCarriesReplyTokenAndLoopHeaders() throws Exception {
        String json = mvc.perform(as(post("/api/v1/tickets"), DEMO, requester, "REQ")
                        .content("{\"type\":\"incident\",\"title\":\"Нет доступа к 1С\",\"description\":\"Ошибка входа\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String number = JsonPath.read(json, "$.number");
        delivery.runOnce(Instant.now());

        MimeMessage sent = Arrays.stream(greenMail.getReceivedMessages())
                .filter(m -> {
                    try {
                        return m.getAllRecipients()[0].toString().contains(requesterEmail);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .findFirst().orElseThrow();
        assertThat(sent.getSubject()).contains(number).contains("[" + number + "]");
        assertThat(sent.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
        assertThat(sent.getHeader(LoopGuard.LOOP_HEADER, null)).isNotBlank();
        assertThat(sent.getHeader("References", null)).contains("eitsm.");
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        sent.writeTo(raw);
        assertThat(raw.toString(StandardCharsets.UTF_8)).contains("[ref:" + number + ":");
        // Тело отправленного письма в очереди стёрто.
        String stored = inDemo(() -> jdbc.sql("""
                        select status || '|' || body_text from mail_outgoing
                         where recipient = :r and kind = 'ticket_created' order by created_at desc limit 1""")
                .param("r", requesterEmail).query(String.class).single());
        assertThat(stored).isEqualTo("sent|");

        // Наше же письмо, вернувшееся в ящик, — петля, а не новая заявка.
        java.io.ByteArrayOutputStream copy = new java.io.ByteArrayOutputStream();
        sent.writeTo(copy);
        boxUser.deliver(new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(copy.toByteArray())));
        TenantContext.runAs(UUID.fromString(DEMO), () -> polling.poll(settings.mailbox(UUID.fromString(mailboxId))
                .orElseThrow()));
        assertThat(logged(sent.getMessageID()).verdict()).isEqualTo("loop");
    }

    @Test
    void settingsAreAdminOnlyAndTenantIsolated() throws Exception {
        mvc.perform(as(get("/api/v1/mail-settings/mailboxes"), DEMO, requester, "AGT")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/mail-settings/mailboxes/" + mailboxId), OTHER, admin, "ADM"))
                .andExpect(status().isNotFound());
        mvc.perform(asAdmin(get("/api/v1/mail-settings/mailboxes/" + mailboxId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretSet").value(true))
                .andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(asAdmin(post("/api/v1/mail-settings/mailboxes/" + mailboxId + "/check")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // Тот же адрес второй раз — конфликт.
        mvc.perform(asAdmin(post("/api/v1/mail-settings/mailboxes")).content("""
                        {"address":"%s","protocol":"smtp"}""".formatted(boxAddress.toUpperCase())))
                .andExpect(status().isConflict());
    }

    static <T> T inDemo(java.util.concurrent.Callable<T> action) {
        try {
            return TenantContext.callAs(UUID.fromString(DEMO), action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long count(String sql, String param) {
        return inDemo(() -> jdbc.sql(sql).param("u", param)
                .query(Long.class).single());
    }
}
