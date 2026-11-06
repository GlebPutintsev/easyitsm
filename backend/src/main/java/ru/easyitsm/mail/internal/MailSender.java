package ru.easyitsm.mail.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Properties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Отправка готового письма через SMTP заказчика или Microsoft Graph. */
@Component
class MailSender {

    private final GraphClient graph;

    MailSender(GraphClient graph) {
        this.graph = graph;
    }

    void send(MimeMessage msg, MailSettings.Transport t) throws MessagingException, IOException {
        if ("graph".equals(t.protocol())) {
            graph.sendMime(new GraphClient.Credentials(t.graphTenant(), t.graphClientId(), t.secret()), t.fromAddress(),
                    MailComposer.bytes(msg));
            return;
        }
        try {
            smtp(t).send(msg);
        } catch (MailException e) {
            throw new MessagingException(e.getMessage(), e);
        }
    }

    /** Проверка подключения к SMTP без отправки письма. */
    void test(MailSettings.Transport t) throws MessagingException, IOException {
        if ("graph".equals(t.protocol())) {
            graph.check(new GraphClient.Credentials(t.graphTenant(), t.graphClientId(), t.secret()), t.fromAddress());
            return;
        }
        smtp(t).testConnection();
    }

    static JavaMailSenderImpl smtp(MailSettings.Transport t) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(t.host());
        String security = t.security() == null ? "starttls" : t.security();
        s.setPort(t.port() != null ? t.port() : switch (security) {
            case "ssl" -> 465;
            case "starttls" -> 587;
            default -> 25;
        });
        if (t.username() != null && !t.username().isBlank()) {
            s.setUsername(t.username());
            s.setPassword(t.secret());
        }
        s.setDefaultEncoding("UTF-8");
        Properties p = new Properties();
        p.put("mail.smtp.auth", String.valueOf(t.username() != null && !t.username().isBlank()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "30000");
        p.put("mail.smtp.writetimeout", "30000");
        if ("starttls".equals(security)) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        } else if ("ssl".equals(security)) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        s.setJavaMailProperties(p);
        return s;
    }
}
