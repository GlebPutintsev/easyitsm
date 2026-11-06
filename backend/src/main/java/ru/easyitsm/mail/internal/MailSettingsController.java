package ru.easyitsm.mail.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.mail.api.MailOutbox;
import ru.easyitsm.mail.api.OutgoingMail;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** «Управление → Почта»: отправка, ящики приёма, журналы входящих и исходящих писем. Только ADM. */
@RestController
@RequestMapping("/api/v1/mail-settings")
@PreAuthorize("hasRole('ADM')")
class MailSettingsController {

    private final MailSettings settings;
    private final MailSender sender;
    private final MailPolling polling;
    private final MailOutboxService outbox;
    private final InboundProcessor inbound;
    private final UserDirectory users;
    private final JdbcClient jdbc;

    MailSettingsController(MailSettings settings, MailSender sender, MailPolling polling, MailOutboxService outbox,
                           InboundProcessor inbound, UserDirectory users, JdbcClient jdbc) {
        this.settings = settings;
        this.sender = sender;
        this.polling = polling;
        this.outbox = outbox;
        this.inbound = inbound;
        this.users = users;
        this.jdbc = jdbc;
    }

    record CheckResult(boolean ok, String error) {
    }

    // ================================================================ отправка

    @GetMapping("/transport")
    ResponseEntity<MailSettings.TransportView> transport() {
        return settings.transportView()
                .map(v -> v.fallback() ? ResponseEntity.ok(v) : ResponseEntity.ok().eTag(ETags.of(v.version())).body(v))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/transport")
    ResponseEntity<MailSettings.TransportView> saveTransport(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MailSettings.TransportInput in) {
        var v = settings.saveTransport(in, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    record TestRequest(@Email @Size(max = 320) String to) {
    }

    /**
     * Проверка отправки: подключение к серверу и тестовое письмо в очередь (на адрес из запроса или почту
     * администратора). Кнопка «Проверить почту» мастера настройки (ADM-01).
     */
    @PostMapping("/transport/test")
    CheckResult testTransport(@Valid @RequestBody(required = false) TestRequest req) {
        var transport = settings.transport()
                .orElseThrow(() -> ApiException.conflict("not_configured", "Отправка почты не настроена"));
        try {
            sender.test(transport);
        } catch (Exception e) {
            return new CheckResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String to = req != null && req.to() != null && !req.to().isBlank() ? req.to().trim()
                : CurrentActor.userId().flatMap(users::findUser).map(UserSummary::email).orElse(null);
        if (to != null) {
            outbox.enqueue(new OutgoingMail("mail_test", to, null, CurrentActor.userId().orElse(null), null, null,
                    "Easy ITSM: проверка отправки почты",
                    "Это тестовое письмо Easy ITSM. Если вы его получили, отправка почты настроена верно.", null));
        }
        return new CheckResult(true, null);
    }

    // ================================================================ ящики

    @GetMapping("/mailboxes")
    List<MailSettings.MailboxView> mailboxes() {
        return settings.mailboxes();
    }

    @GetMapping("/mailboxes/{id}")
    ResponseEntity<MailSettings.MailboxView> mailbox(@PathVariable UUID id) {
        var v = settings.requireMailbox(id).view();
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/mailboxes")
    ResponseEntity<MailSettings.MailboxView> createMailbox(@Valid @RequestBody MailSettings.MailboxInput in) {
        var v = settings.createMailbox(in);
        return ResponseEntity.created(URI.create("/api/v1/mail-settings/mailboxes/" + v.id()))
                .eTag(ETags.of(v.version())).body(v);
    }

    @PutMapping("/mailboxes/{id}")
    ResponseEntity<MailSettings.MailboxView> updateMailbox(@PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MailSettings.MailboxInput in) {
        var v = settings.updateMailbox(id, in, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @DeleteMapping("/mailboxes/{id}")
    ResponseEntity<Void> deleteMailbox(@PathVariable UUID id) {
        settings.deleteMailbox(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mailboxes/{id}/check")
    CheckResult checkMailbox(@PathVariable UUID id) {
        var box = settings.requireMailbox(id);
        try {
            polling.check(box);
            return new CheckResult(true, null);
        } catch (Exception e) {
            return new CheckResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Забрать почту сейчас, не дожидаясь планового опроса. */
    @PostMapping("/mailboxes/{id}/poll")
    Map<String, Object> pollMailbox(@PathVariable UUID id) {
        var box = settings.requireMailbox(id);
        if ("smtp".equals(box.protocol())) {
            throw ApiException.conflict("not_pollable", "Ящик входящего SMTP получает письма сам");
        }
        int n = polling.poll(box);
        var after = settings.requireMailbox(id);
        return Map.of("processed", n, "error", after.lastError() == null ? "" : after.lastError());
    }

    // ================================================================ журналы

    record InboundView(UUID id, UUID mailboxId, String messageId, String fromAddress, String fromName, String subject,
                       Instant receivedAt, String spf, String dkim, String dmarc, String verdict, String detail,
                       UUID ticketId, boolean canRelease, int size) {
    }

    @GetMapping("/inbound")
    @Transactional(readOnly = true)
    public CursorPage<InboundView> inbound(@RequestParam(required = false) String verdict,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        PageParams page = PageParams.of(cursor, limit);
        KeysetCursor after = page.keyset();
        List<InboundView> rows = jdbc.sql("""
                select id, mailbox_id, message_id, from_address, from_name, subject, received_at, spf, dkim, dmarc,
                       verdict, detail, ticket_id, raw is not null as has_raw, raw_size
                  from mail_inbound
                 where (cast(:verdict as text) is null or verdict = :verdict)
                   and (cast(:after as uuid) is null or id < cast(:after as uuid))
                 order by id desc
                 limit :limit""")
                .param("verdict", verdict).param("after", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new InboundView(rs.getObject("id", UUID.class), rs.getObject("mailbox_id", UUID.class),
                        rs.getString("message_id"), rs.getString("from_address"), rs.getString("from_name"),
                        rs.getString("subject"), rs.getTimestamp("received_at").toInstant(), rs.getString("spf"),
                        rs.getString("dkim"), rs.getString("dmarc"), rs.getString("verdict"), rs.getString("detail"),
                        rs.getObject("ticket_id", UUID.class),
                        "quarantined".equals(rs.getString("verdict")) && rs.getBoolean("has_raw"),
                        rs.getInt("raw_size")))
                .list();
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.receivedAt().toString(), r.id()));
    }

    /** Принять письмо из карантина (CHN-02): создаст заявку или ответ, как обычное письмо. */
    @PostMapping("/inbound/{id}/release")
    InboundProcessor.Result release(@PathVariable UUID id) {
        return inbound.release(id);
    }

    @GetMapping("/outgoing")
    CursorPage<MailOutboxService.DeliveryView> outgoing(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) Integer limit) {
        return outbox.list(status, PageParams.of(cursor, limit));
    }

    @PostMapping("/outgoing/{id}/retry")
    ResponseEntity<Void> retry(@PathVariable UUID id) {
        outbox.retry(id);
        return ResponseEntity.noContent().build();
    }
}
