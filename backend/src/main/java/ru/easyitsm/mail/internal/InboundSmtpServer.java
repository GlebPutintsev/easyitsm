package ru.easyitsm.mail.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.TooMuchDataException;
import org.subethamail.smtp.server.SMTPServer;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Встроенный входящий SMTP (CHN-01): почтовый сервер заказчика пересылает письма на адрес ящика с протоколом
 * {@code smtp}. Принимаются только получатели — адреса таких ящиков (не открытый релей), арендатор определяется
 * по адресу. SPF проверяется по адресу подключившегося сервера. Порт слушает только роль worker, если
 * {@code easyitsm.mail.inbound-smtp.enabled=true}; наружу его открывает NetworkPolicy.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.mail.inbound-smtp.enabled", havingValue = "true")
class InboundSmtpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboundSmtpServer.class);

    private final JdbcClient jdbc;
    private final MailSettings settings;
    private final InboundProcessor processor;
    private final MailProperties props;
    private SMTPServer server;

    InboundSmtpServer(JdbcClient jdbc, MailSettings settings, InboundProcessor processor, MailProperties props) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.processor = processor;
        this.props = props;
    }

    @Override
    public void start() {
        var cfg = props.inboundSmtp();
        SMTPServer.Builder b = new SMTPServer.Builder()
                .port(cfg.portOrDefault())
                .softwareName("Easy ITSM")
                .maxMessageSize(props.maxMessageSize())
                .maxRecipients(20)
                .maxConnections(100)
                .connectionTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .messageHandlerFactory(Session::new);
        if (cfg.hostName() != null && !cfg.hostName().isBlank()) {
            b.hostName(cfg.hostName());
        }
        if (cfg.bindAddress() != null && !cfg.bindAddress().isBlank()) {
            try {
                b.bindAddress(InetAddress.getByName(cfg.bindAddress()));
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Неверный адрес easyitsm.mail.inbound-smtp.bind-address", e);
            }
        }
        server = b.build();
        server.start();
        log.info("Входящий SMTP слушает порт {}", server.getPortAllocated());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    int port() {
        return server == null ? -1 : server.getPortAllocated();
    }

    private record Target(UUID tenantId, UUID mailboxId) {
    }

    private final class Session implements MessageHandler {

        private final MessageContext ctx;
        private String from;
        private final List<Target> targets = new ArrayList<>();

        Session(MessageContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void from(String from) {
            this.from = from;
        }

        @Override
        public void recipient(String recipient) throws RejectException {
            Optional<Target> t = jdbc.sql("select tenant_id, id from mail_mailbox_by_address(:a)")
                    .param("a", recipient == null ? "" : recipient.trim())
                    .query((rs, n) -> new Target(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                    .optional();
            if (t.isEmpty()) {
                throw new RejectException(550, "5.1.1 Mailbox unavailable");
            }
            if (!targets.contains(t.get())) {
                targets.add(t.get());
            }
        }

        @Override
        public String data(InputStream data) throws RejectException, TooMuchDataException, IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = data.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > props.maxMessageSize()) {
                    throw new TooMuchDataException("Message too large");
                }
            }
            byte[] raw = out.toByteArray();
            String ip = ctx.getRemoteAddress() instanceof InetSocketAddress isa && isa.getAddress() != null
                    ? isa.getAddress().getHostAddress() : null;
            var envelope = new MailAuthenticator.Envelope(ip, from, ctx.getHelo().orElse(null));
            for (Target t : targets) {
                try {
                    TenantContext.runAs(t.tenantId(), () -> settings.mailbox(t.mailboxId())
                            .ifPresent(box -> processor.process(box, raw, envelope)));
                } catch (RuntimeException e) {
                    log.error("Ошибка обработки письма входящего SMTP", e);
                    throw new RejectException(451, "4.3.0 Temporary failure, try again later");
                }
            }
            return null;
        }

        @Override
        public void done() {
            // сессия без состояния вне памяти
        }
    }
}
