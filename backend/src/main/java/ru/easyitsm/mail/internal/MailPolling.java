package ru.easyitsm.mail.internal;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Забирает почту из ящиков IMAP и Microsoft Graph (CHN-01). Берутся непрочитанные письма, после обработки
 * письмо отмечается прочитанным; если отметка не удалась, повторную обработку отсечёт Message-ID.
 */
@Service
class MailPolling {

    private static final Logger log = LoggerFactory.getLogger(MailPolling.class);
    static final int MAX_PER_POLL = 50;

    private final JdbcClient jdbc;
    private final MailSettings settings;
    private final InboundProcessor processor;
    private final GraphClient graph;
    private final MailProperties props;

    MailPolling(JdbcClient jdbc, MailSettings settings, InboundProcessor processor, GraphClient graph,
                MailProperties props) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.processor = processor;
        this.graph = graph;
        this.props = props;
    }

    /** Опрашивает ящики, у которых подошёл срок. */
    void runOnce(Instant now) {
        record Due(UUID tenantId, UUID id) {
        }
        List<Due> due = jdbc.sql("select tenant_id, id from mail_mailboxes_due(:before)")
                .param("before", OffsetDateTime.ofInstant(now.minus(props.pollInterval()).plusSeconds(1), ZoneOffset.UTC))
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
        for (Due d : due) {
            TenantContext.runAs(d.tenantId(), () -> settings.mailbox(d.id()).ifPresent(this::poll));
        }
    }

    /** @return число обработанных писем */
    int poll(MailSettings.Mailbox box) {
        try {
            int n = "graph".equals(box.protocol()) ? pollGraph(box) : pollImap(box);
            settings.markPolled(box.id(), null);
            return n;
        } catch (Exception e) {
            log.warn("Ящик {} не опрошен: {}", box.address(), e.toString());
            settings.markPolled(box.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            return 0;
        }
    }

    /** Проверка подключения из «Управления»: вход и открытие папки, без чтения писем. */
    void check(MailSettings.Mailbox box) throws MessagingException, IOException {
        if ("graph".equals(box.protocol())) {
            graph.check(credentials(box), box.address());
        } else if ("imap".equals(box.protocol())) {
            try (Store store = connect(box)) {
                Folder folder = store.getFolder(box.folder());
                folder.open(Folder.READ_ONLY);
                folder.close(false);
            }
        }
    }

    private int pollImap(MailSettings.Mailbox box) throws MessagingException, IOException {
        int processed = 0;
        try (Store store = connect(box)) {
            Folder folder = store.getFolder(box.folder());
            folder.open(Folder.READ_WRITE);
            try {
                Message[] unseen = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                for (Message m : unseen) {
                    if (processed >= MAX_PER_POLL) {
                        break;
                    }
                    if (m.getSize() > props.maxMessageSize()) {
                        m.setFlag(Flags.Flag.SEEN, true);
                        log.warn("Письмо больше {} байт в ящике {} пропущено", props.maxMessageSize(), box.address());
                        continue;
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    m.writeTo(out);
                    processor.process(box, out.toByteArray(), null);
                    m.setFlag(Flags.Flag.SEEN, true);
                    processed++;
                }
            } finally {
                folder.close(false);
            }
        }
        return processed;
    }

    private int pollGraph(MailSettings.Mailbox box) throws IOException {
        GraphClient.Credentials c = credentials(box);
        int processed = 0;
        for (String id : graph.unread(c, box.address(), box.folder(), MAX_PER_POLL)) {
            byte[] mime = graph.mime(c, box.address(), id);
            if (mime.length <= props.maxMessageSize()) {
                processor.process(box, mime, null);
            }
            graph.markRead(c, box.address(), id);
            processed++;
        }
        return processed;
    }

    private static GraphClient.Credentials credentials(MailSettings.Mailbox box) {
        return new GraphClient.Credentials(box.graphTenant(), box.graphClientId(), box.secret());
    }

    private static Store connect(MailSettings.Mailbox box) throws MessagingException {
        String security = box.security() == null ? "ssl" : box.security();
        String protocol = "ssl".equals(security) ? "imaps" : "imap";
        Properties p = new Properties();
        p.put("mail.store.protocol", protocol);
        p.put("mail." + protocol + ".connectiontimeout", "10000");
        p.put("mail." + protocol + ".timeout", "60000");
        p.put("mail." + protocol + ".writetimeout", "30000");
        if ("starttls".equals(security)) {
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", "true");
        }
        if ("ssl".equals(security)) {
            p.put("mail.imaps.ssl.checkserveridentity", "true");
        }
        Store store = Session.getInstance(p).getStore(protocol);
        int port = box.port() != null ? box.port() : "ssl".equals(security) ? 993 : 143;
        store.connect(box.host(), port, box.username(), box.secret());
        return store;
    }
}
