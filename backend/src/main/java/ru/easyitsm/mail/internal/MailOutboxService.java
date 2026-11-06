package ru.easyitsm.mail.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import ru.easyitsm.mail.api.MailOutbox;
import ru.easyitsm.mail.api.OutgoingMail;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * Очередь исходящих писем. К письму заявки добавляются номер в теме, строка-разделитель для ответа над ней
 * и строка {@code [ref:…]} с токеном ответа; Message-ID и заголовки цепочки ставит {@link MailComposer}.
 */
@Service
class MailOutboxService implements MailOutbox {

    private final JdbcClient jdbc;
    private final MailSettings settings;
    private final ReplyTokens tokens;

    MailOutboxService(JdbcClient jdbc, MailSettings settings, ReplyTokens tokens) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tokens = tokens;
    }

    @Override
    @Transactional
    public UUID enqueue(OutgoingMail mail) {
        UUID tenant = TenantContext.require();
        UUID id = Ids.newId();
        String recipient = mail.recipient().trim();
        boolean own = settings.ownAddresses().contains(recipient.toLowerCase(Locale.ROOT));
        String subject = mail.subject().replaceAll("[\\r\\n]+", " ").strip();
        String text = mail.text();
        String html = mail.html();
        if (mail.ticketId() != null && mail.ticketNumber() != null) {
            if (!subject.contains("[" + mail.ticketNumber() + "]")) {
                subject = subject + " [" + mail.ticketNumber() + "]";
            }
            String ref = tokens.bodyRef(tenant, mail.ticketId(), mail.ticketNumber());
            text = MailText.REPLY_MARKER + "\n\n" + text + "\n\n" + ref + "\n";
            if (html != null) {
                html = "<div style=\"color:#8a8f98;font-size:11px\">" + HtmlUtils.htmlEscape(MailText.REPLY_MARKER)
                        + "</div>" + html + "<div style=\"color:#8a8f98;font-size:10px;margin-top:24px\">"
                        + HtmlUtils.htmlEscape(ref) + "</div>";
            }
        }
        String domain = settings.transport().map(MailSettings.Transport::domain).orElse("easyitsm.local");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                insert into mail_outgoing (id, tenant_id, kind, recipient, recipient_name, recipient_user_id, ticket_id,
                    subject, body_text, body_html, message_id, status, attempts, next_attempt_at, last_error,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :kind, :recipient, :name, :userId, :ticket, :subject, :text, :html, :messageId,
                    :status, 0, :next, :error, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", tenant).param("kind", mail.kind()).param("recipient", recipient)
                .param("name", mail.recipientName()).param("userId", mail.recipientUserId())
                .param("ticket", mail.ticketId()).param("subject", MailSettings.truncate(subject, 998))
                .param("text", own ? "" : text).param("html", own ? null : html)
                .param("messageId", tokens.messageId(tenant, mail.ticketId(), id, domain))
                .param("status", own ? "suppressed" : "pending").param("next", own ? null : now)
                .param("error", own ? "адрес ящика приёма заявок: письмо вызвало бы петлю" : null)
                .param("now", now).param("actor", CurrentActor.userId().orElse(null))
                .update();
        return id;
    }

    record DeliveryView(UUID id, String kind, String recipient, UUID ticketId, String subject, String status,
                        int attempts, Instant nextAttemptAt, String lastError, Instant sentAt, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public CursorPage<DeliveryView> list(String status, PageParams page) {
        KeysetCursor after = page.keyset();
        List<DeliveryView> rows = jdbc.sql("""
                select id, kind, recipient, ticket_id, subject, status, attempts, next_attempt_at, last_error, sent_at,
                       created_at
                  from mail_outgoing
                 where (cast(:status as text) is null or status = :status)
                   and (cast(:after as uuid) is null or id < cast(:after as uuid))
                 order by id desc
                 limit :limit""")
                .param("status", status).param("after", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new DeliveryView(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("recipient"), rs.getObject("ticket_id", UUID.class), rs.getString("subject"),
                        rs.getString("status"), rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")),
                        rs.getString("last_error"), instant(rs.getTimestamp("sent_at")),
                        instant(rs.getTimestamp("created_at"))))
                .list();
        return CursorPage.of(rows, page.limit(), d -> new KeysetCursor(d.createdAt().toString(), d.id()));
    }

    /** Повторить неотправленное письмо: только failed, тело которого ещё хранится. */
    @Transactional
    public void retry(UUID id) {
        int n = jdbc.sql("""
                update mail_outgoing set status = 'pending', next_attempt_at = now(), attempts = 0, last_error = null,
                    updated_at = now(), version = version + 1
                 where id = :id and status = 'failed'""").param("id", id).update();
        if (n == 0) {
            throw ru.easyitsm.platform.web.ApiException.conflict("not_failed", "Повторить можно только неотправленное письмо");
        }
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
