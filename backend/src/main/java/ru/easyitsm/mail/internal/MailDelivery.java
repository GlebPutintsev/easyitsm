package ru.easyitsm.mail.internal;

import jakarta.mail.SendFailedException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.mail.events.MailDeliveryFailed;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Отправка очереди писем во всех арендаторах. Запись блокируется ({@code for update skip locked}), поэтому
 * несколько подов worker не отправят одно письмо дважды. После успешной отправки тело письма стирается:
 * в журнале остаются получатель, тема и статус.
 */
@Service
class MailDelivery {

    private static final Logger log = LoggerFactory.getLogger(MailDelivery.class);
    static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final MailSettings settings;
    private final MailComposer composer;
    private final MailSender sender;
    private final MailProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    MailDelivery(JdbcClient jdbc, MailSettings settings, MailComposer composer, MailSender sender,
                 MailProperties props, ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.composer = composer;
        this.sender = sender;
        this.props = props;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Один проход: до {@value #BATCH} писем, срок отправки которых наступил. */
    int runOnce(Instant now) {
        record Due(UUID tenantId, UUID id) {
        }
        List<Due> due = jdbc.sql("select tenant_id, id from mail_outgoing_due(:now, :limit)")
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).param("limit", BATCH)
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
        for (Due d : due) {
            try {
                TenantContext.runAs(d.tenantId(), () -> deliver(d.id()));
            } catch (RuntimeException e) {
                log.error("Ошибка отправки письма {}", d.id(), e);
            }
        }
        return due.size();
    }

    void deliver(UUID id) {
        tx.executeWithoutResult(s -> {
            Optional<Row> found = jdbc.sql("""
                    select id, tenant_id, kind, recipient, recipient_name, recipient_user_id, ticket_id, subject,
                           body_text, body_html, message_id, attempts
                      from mail_outgoing
                     where id = :id and status = 'pending'
                     for update skip locked""")
                    .param("id", id)
                    .query((rs, n) -> new Row(new MailComposer.Queued(rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class), rs.getString("recipient"),
                            rs.getString("recipient_name"), rs.getObject("ticket_id", UUID.class),
                            rs.getString("subject"), rs.getString("body_text"), rs.getString("body_html"),
                            rs.getString("message_id")), rs.getString("kind"),
                            rs.getObject("recipient_user_id", UUID.class), rs.getInt("attempts")))
                    .optional();
            if (found.isEmpty()) {
                return;
            }
            Row row = found.get();
            String error;
            boolean permanent = false;
            Optional<MailSettings.Transport> transport = settings.transport();
            if (transport.isEmpty()) {
                error = "Отправка почты не настроена";
            } else {
                try {
                    sender.send(composer.compose(row.queued(), transport.get()), transport.get());
                    jdbc.sql("""
                            update mail_outgoing set status = 'sent', sent_at = now(), attempts = attempts + 1,
                                body_text = '', body_html = null, last_error = null, next_attempt_at = null,
                                updated_at = now(), version = version + 1
                             where id = :id""").param("id", id).update();
                    return;
                } catch (SendFailedException e) {
                    error = "Адрес отклонён сервером: " + e.getMessage();
                    permanent = e.getValidUnsentAddresses() == null || e.getValidUnsentAddresses().length == 0;
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
            int attempts = row.attempts() + 1;
            List<Duration> delays = props.retryDelays();
            if (permanent || attempts > delays.size()) {
                jdbc.sql("""
                        update mail_outgoing set status = 'failed', attempts = :attempts, last_error = :error,
                            next_attempt_at = null, updated_at = now(), version = version + 1
                         where id = :id""")
                        .param("id", id).param("attempts", attempts)
                        .param("error", MailSettings.truncate(String.valueOf(error), 1000)).update();
                events.publishEvent(new MailDeliveryFailed(TenantContext.require(), id, row.kind(),
                        row.queued().recipient(), row.recipientUserId(), row.queued().ticketId(),
                        MailSettings.truncate(String.valueOf(error), 500), Instant.now()));
                log.warn("Письмо {} не доставлено после {} попыток: {}", id, attempts, error);
            } else {
                jdbc.sql("""
                        update mail_outgoing set attempts = :attempts, last_error = :error, next_attempt_at = :next,
                            updated_at = now()
                         where id = :id""")
                        .param("id", id).param("attempts", attempts)
                        .param("error", MailSettings.truncate(String.valueOf(error), 1000))
                        .param("next", java.sql.Timestamp.from(Instant.now().plus(delays.get(attempts - 1))))
                        .update();
            }
        });
    }

    private record Row(MailComposer.Queued queued, String kind, UUID recipientUserId, int attempts) {
    }
}
