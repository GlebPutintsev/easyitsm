package ru.easyitsm.mail.internal;

import java.sql.Timestamp;
import java.time.Instant;
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
import ru.easyitsm.attachments.api.AttachmentIntake;
import ru.easyitsm.attachments.api.IncomingFile;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.mail.events.InboundMailProcessed;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.SecretScanner;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Обработка входящего письма (поток 6.4): защита от петель и автоответов, проверка подлинности, определение
 * отправителя, связь с заявкой по токену ответа, затем новая заявка или запись в ленте — в одной транзакции
 * с журналом. Вызывается в арендаторе ящика.
 * <p>
 * Кто и как пишет в существующую заявку письмом: заявитель и получатель — публичный ответ (в том числе возврат
 * решённой заявки в работу, INC-08); исполнитель и наблюдатели — внутренняя заметка, чтобы ответ на
 * уведомление случайно не ушёл заявителю. Любой другой отправитель с верным токеном получает новую заявку:
 * чужой человек не может дописать в заявку, в которой не участвует. Ответ в закрытую заявку — тоже новая
 * заявка со ссылкой на прежнюю.
 */
@Service
class InboundProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundProcessor.class);

    private final JdbcClient jdbc;
    private final MimeParser parser;
    private final MailAuthenticator authenticator;
    private final ReplyTokens tokens;
    private final MailSettings settings;
    private final MailProperties props;
    private final UserDirectory users;
    private final TicketCommands tickets;
    private final TicketQueries ticketQueries;
    private final SecretScanner secrets;
    private final ApplicationEventPublisher events;
    private final AttachmentIntake attachments;
    private final TransactionTemplate tx;

    InboundProcessor(JdbcClient jdbc, MimeParser parser, MailAuthenticator authenticator, ReplyTokens tokens,
                     MailSettings settings, MailProperties props, UserDirectory users, TicketCommands tickets,
                     TicketQueries ticketQueries, SecretScanner secrets, ApplicationEventPublisher events,
                     AttachmentIntake attachments, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.parser = parser;
        this.authenticator = authenticator;
        this.tokens = tokens;
        this.settings = settings;
        this.props = props;
        this.users = users;
        this.tickets = tickets;
        this.ticketQueries = ticketQueries;
        this.secrets = secrets;
        this.events = events;
        this.attachments = attachments;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record Result(String verdict, UUID ticketId, UUID inboundId) {
    }

    /** Итог для журнала: verdict, подробность, заявка. */
    private record Verdict(String verdict, String detail, UUID ticketId, boolean keepRaw) {
    }

    /**
     * @param envelope данные SMTP-сессии для SPF; null — письмо забрано из IMAP или Graph
     * @return итог; {@code duplicate} — письмо с таким Message-ID уже обработано, в журнал не пишется
     */
    Result process(MailSettings.Mailbox box, byte[] raw, MailAuthenticator.Envelope envelope) {
        ParsedMail mail;
        try {
            mail = parser.parse(raw);
        } catch (Exception e) {
            log.warn("Письмо в ящике {} не разобрано: {}", box.address(), e.toString());
            return logOnly(box, null, raw, new Verdict("error", "письмо не разобрано", null, false));
        }
        if (mail.messageId() != null && seen(mail.messageId())) {
            return new Result("duplicate", null, null);
        }
        try {
            return tx.execute(s -> {
                Verdict v = decide(box, mail, envelope, false);
                UUID id = log(box, mail, v, null);
                return new Result(v.verdict(), v.ticketId(), id);
            });
        } catch (AlreadyProcessed e) {
            return new Result("duplicate", null, null);
        } catch (RuntimeException e) {
            log.warn("Ошибка обработки письма {} в ящике {}", mail.messageId(), box.address(), e);
            String detail = e instanceof ApiException api ? api.code() + ": " + api.getMessage() : "внутренняя ошибка";
            return logOnly(box, mail, raw, new Verdict("error", detail, null, false));
        }
    }

    /**
     * Принять письмо из карантина: администратор проверил его вручную. Подлинность больше не проверяется,
     * остальные правила (петли, отправитель, токен) — как обычно.
     */
    Result release(UUID inboundId) {
        return tx.execute(s -> {
            var row = jdbc.sql("select mailbox_id, raw, verdict from mail_inbound where id = :id for update")
                    .param("id", inboundId)
                    .query((rs, n) -> new Object[] {rs.getObject("mailbox_id", UUID.class), rs.getBytes("raw"),
                            rs.getString("verdict")})
                    .optional().orElseThrow(() -> ApiException.notFound("Письмо"));
            if (!"quarantined".equals(row[2]) || row[1] == null || row[0] == null) {
                throw ApiException.conflict("not_quarantined", "Письмо не в карантине или ящик удалён");
            }
            MailSettings.Mailbox box = settings.requireMailbox((UUID) row[0]);
            ParsedMail mail;
            try {
                mail = parser.parse((byte[]) row[1]);
            } catch (Exception e) {
                throw ApiException.conflict("unparseable", "Письмо не разбирается");
            }
            Verdict v = decide(box, mail, null, true);
            String verdict = v.ticketId() != null ? "released" : v.verdict();
            jdbc.sql("""
                    update mail_inbound set verdict = :verdict, detail = :detail, ticket_id = :ticket, raw = null,
                        updated_at = now(), updated_by = :actor, version = version + 1
                     where id = :id""")
                    .param("id", inboundId).param("verdict", verdict).param("detail", v.detail())
                    .param("ticket", v.ticketId())
                    .param("actor", ru.easyitsm.platform.security.CurrentActor.userId().orElse(null))
                    .update();
            events.publishEvent(new InboundMailProcessed(TenantContext.require(), inboundId, box.id(),
                    mail.fromAddress(), verdict, v.ticketId(), Instant.now()));
            return new Result(verdict, v.ticketId(), inboundId);
        });
    }

    private Verdict decide(MailSettings.Mailbox box, ParsedMail mail, MailAuthenticator.Envelope envelope,
                           boolean released) {
        var loop = LoopGuard.check(mail, settings.ownAddresses());
        if (loop.isPresent()) {
            return new Verdict(loop.get().kind() == LoopGuard.Kind.LOOP ? "loop" : "auto_reply", loop.get().reason(),
                    null, false);
        }
        if (!released && recentFrom(mail.fromAddress()) >= props.maxPerSenderPerHour()) {
            return new Verdict("rate_limited", "больше " + props.maxPerSenderPerHour() + " писем за час с адреса",
                    null, false);
        }
        String authDetail = null;
        if (!released) {
            MailAuthenticator.Outcome auth = authenticator.check(mail, box, envelope);
            authDetail = auth.detail();
            currentAuth.set(auth);
            if (auth.decision() == MailAuthenticator.Decision.REJECT) {
                return new Verdict("auth_failed", auth.detail(), null, false);
            }
            if (auth.decision() == MailAuthenticator.Decision.QUARANTINE) {
                return new Verdict("quarantined", auth.detail(), null, true);
            }
        }
        Optional<UserSummary> sender = users.findUserByEmail(mail.fromAddress()).filter(UserSummary::active);
        if (sender.isEmpty()) {
            // Ответ неизвестному адресу не отправляем: это источник обратного спама (backscatter).
            return new Verdict("unknown_sender", "адрес не найден среди активных пользователей", null, false);
        }
        UUID userId = sender.get().id();
        String text = body(mail);
        Optional<TicketSnapshot> linked = linkedTicket(mail);
        if (linked.isPresent()) {
            TicketSnapshot t = linked.get();
            Visibility visibility = null;
            if (userId.equals(t.requesterId()) || userId.equals(t.createdForId())) {
                visibility = Visibility.PUBLIC;
            } else if (userId.equals(t.assigneeId()) || ticketQueries.watchers(t.id()).contains(userId)) {
                visibility = Visibility.INTERNAL;
            }
            if (visibility != null && !t.statusCategory().isFinal()) {
                tickets.addComment(t.id(), userId, text, visibility);
                String files = files(t.id(), mail, userId,
                        visibility == Visibility.INTERNAL ? "internal" : "public");
                return new Verdict("comment_added", join(join(authDetail, "ответ в " + t.number()), files), t.id(),
                        false);
            }
            if (visibility != null) {
                UUID created = create(box, mail, userId, "Продолжение заявки " + t.number() + "\n\n" + text);
                return new Verdict("ticket_created", join(join(authDetail, t.number() + " закрыта, создана новая"),
                        files(created, mail, userId, "public")), created, false);
            }
        }
        UUID created = create(box, mail, userId, text);
        return new Verdict("ticket_created", join(authDetail, files(created, mail, userId, "public")), created, false);
    }

    private final ThreadLocal<MailAuthenticator.Outcome> currentAuth = new ThreadLocal<>();

    private UUID create(MailSettings.Mailbox box, ParsedMail mail, UUID requester, String description) {
        String title = cleanSubject(mail.subject());
        if (title.isBlank()) {
            title = "Письмо от " + mail.fromAddress();
        }
        title = secrets.mask(title);
        if (title.length() > 255) {
            title = title.substring(0, 254) + "…";
        }
        TicketType type = "request".equals(box.ticketType()) ? TicketType.REQUEST : TicketType.INCIDENT;
        NewTicket n = new NewTicket(type, title, description, requester, null, null, Channel.EMAIL, null, null, null,
                null, null, null, box.groupId(), null, null, false, requester);
        return tickets.create(n).id();
    }

    /**
     * Вложения письма — в файлы заявки через карантин и антивирус (модуль attachments). Хранилище недоступно —
     * заявка всё равно создаётся, а в журнал письма попадает отметка: имена файлов остались в тексте.
     *
     * @return подробность для журнала письма или null, если всё принято
     */
    private String files(UUID ticketId, ParsedMail mail, UUID sender, String visibility) {
        int failed = 0;
        int blocked = 0;
        for (ParsedMail.MailFile f : mail.attachments()) {
            try {
                var accepted = attachments.accept(new IncomingFile("ticket", ticketId, f.name(), f.contentType(),
                        f.content(), visibility, sender, "mail"));
                if (accepted.reason() != null) {
                    blocked++;
                }
            } catch (AttachmentIntake.StorageUnavailable e) {
                log.warn("Вложение {} письма {} не сохранено: {}", f.name(), mail.messageId(), e.getMessage());
                failed++;
            }
        }
        if (failed > 0) {
            return "вложения не сохранены (" + failed + "): хранилище файлов недоступно";
        }
        return blocked > 0 ? "вложения не приняты (" + blocked + "): размер или число файлов" : null;
    }

    private String body(ParsedMail mail) {
        String text = mail.text().isBlank() ? "(письмо без текста)" : mail.text();
        if (!mail.attachments().isEmpty()) {
            // Сами файлы — в разделе «Файлы» заявки после проверки; имена в тексте остаются для истории переписки.
            text += "\n\n_Вложения в письме: " + String.join(", ", mail.attachments().stream().limit(20)
                    .map(ParsedMail.MailFile::name).toList()) + "_";
        }
        // Секреты в тексте маскирует модуль заявок (TCK-17); строку токена из цитаты убираем сами.
        return ReplyTokens.BODY_REF.matcher(text).replaceAll("").strip();
    }

    private Optional<TicketSnapshot> linkedTicket(ParsedMail mail) {
        UUID tenant = TenantContext.require();
        String refs = (mail.inReplyTo() == null ? "" : mail.inReplyTo()) + " "
                + (mail.references() == null ? "" : mail.references());
        Optional<UUID> byHeader = tokens.fromHeaders(tenant, refs);
        if (byHeader.isPresent()) {
            return ticketQueries.find(byHeader.get());
        }
        String raw = new String(mail.raw(), java.nio.charset.StandardCharsets.ISO_8859_1);
        Optional<ReplyTokens.BodyRef> ref = tokens.fromText(mail.subject() + "\n" + mail.text())
                .or(() -> tokens.fromText(raw));
        if (ref.isPresent()) {
            Optional<TicketSnapshot> t = ticketQueries.findByNumber(ref.get().number());
            if (t.isPresent() && tokens.verify(tenant, t.get().id(), ref.get().token())) {
                return t;
            }
        }
        return Optional.empty();
    }

    /** Убирает «Re:», «Fwd:», «Ответ:» и метку {@code [INC-000123]} из темы. */
    static String cleanSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String s = subject.strip();
        String prev;
        do {
            prev = s;
            s = s.replaceFirst("(?iu)^(re|fw|fwd|aw|wg|ответ|отв|пересл|ha|тема)\\s*(\\[\\d+])?\\s*:\\s*", "");
        } while (!s.equals(prev));
        return s.replaceAll("\\s*\\[[A-Z][A-Z0-9]{1,9}-\\d{1,12}]\\s*$", "").strip();
    }

    private boolean seen(String messageId) {
        return jdbc.sql("select exists (select 1 from mail_inbound where message_id = :m)").param("m", messageId)
                .query(Boolean.class).single();
    }

    private long recentFrom(String from) {
        if (from == null) {
            return 0;
        }
        return jdbc.sql("""
                select count(*) from mail_inbound
                 where lower(from_address) = lower(:from) and received_at > now() - interval '1 hour'""")
                .param("from", from).query(Long.class).single();
    }

    private Result logOnly(MailSettings.Mailbox box, ParsedMail mail, byte[] raw, Verdict v) {
        try {
            return tx.execute(s -> new Result(v.verdict(), null, log(box, mail, v, raw)));
        } catch (AlreadyProcessed e) {
            return new Result("duplicate", null, null);
        }
    }

    private UUID log(MailSettings.Mailbox box, ParsedMail mail, Verdict v, byte[] rawOverride) {
        MailAuthenticator.Outcome auth = currentAuth.get();
        currentAuth.remove();
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        byte[] raw = mail == null ? rawOverride : mail.raw();
        String subject = mail == null ? null : secrets.mask(MailSettings.truncate(mail.subject(), 998));
        try {
            jdbc.sql("""
                    insert into mail_inbound (id, tenant_id, mailbox_id, message_id, from_address, from_name, subject,
                        received_at, spf, dkim, dmarc, verdict, detail, ticket_id, raw, raw_size,
                        created_at, updated_at, version)
                    values (:id, :tenant, :mailbox, :messageId, :from, :fromName, :subject, :now, :spf, :dkim, :dmarc,
                        :verdict, :detail, :ticket, :raw, :size, :now, :now, 0)""")
                    .param("id", id).param("tenant", TenantContext.require()).param("mailbox", box.id())
                    .param("messageId", mail == null || mail.messageId() == null ? null
                            : MailSettings.truncate(mail.messageId(), 998))
                    .param("from", mail == null || mail.fromAddress() == null ? null
                            : MailSettings.truncate(mail.fromAddress(), 320))
                    .param("fromName", mail == null ? null : mail.fromName())
                    .param("subject", subject).param("now", now)
                    .param("spf", auth == null ? "none" : auth.spf()).param("dkim", auth == null ? "none" : auth.dkim())
                    .param("dmarc", auth == null ? "none" : auth.dmarc())
                    .param("verdict", v.verdict()).param("detail", v.detail()).param("ticket", v.ticketId())
                    .param("raw", v.keepRaw() ? raw : null)
                    .param("size", raw == null ? 0 : raw.length)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Тот же Message-ID обработал параллельный опрос: откатываем эту обработку целиком.
            throw new AlreadyProcessed();
        }
        events.publishEvent(new InboundMailProcessed(TenantContext.require(), id, box.id(),
                mail == null ? null : mail.fromAddress(), v.verdict(), v.ticketId(), now.toInstant()));
        return id;
    }

    private static final class AlreadyProcessed extends RuntimeException {
        AlreadyProcessed() {
            super("Письмо уже обработано", null, false, false);
        }
    }

    private static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + "; " + b;
    }

    static List<String> verdicts() {
        return List.of("ticket_created", "comment_added", "auto_reply", "loop", "rate_limited", "auth_failed",
                "quarantined", "unknown_sender", "released", "error");
    }
}
