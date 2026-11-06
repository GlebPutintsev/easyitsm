package ru.easyitsm.mail.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Сборка исходящего письма. Заголовки защиты от петель: Auto-Submitted (RFC 3834), X-Auto-Response-Suppress
 * (Exchange не шлёт автоответы «Нет на месте»), собственная метка {@value LoopGuard#LOOP_HEADER}.
 * Письма одной заявки собираются клиентами в беседу по In-Reply-To и References.
 */
@Component
class MailComposer {

    private static final Session SESSION = Session.getInstance(new Properties());

    private final ReplyTokens tokens;

    MailComposer(ReplyTokens tokens) {
        this.tokens = tokens;
    }

    record Queued(UUID id, UUID tenantId, String recipient, String recipientName, UUID ticketId, String subject,
                  String text, String html, String messageId) {
    }

    MimeMessage compose(Queued q, MailSettings.Transport transport) throws MessagingException {
        final String messageId = q.messageId();
        MimeMessage msg = new MimeMessage(SESSION) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", messageId);
            }
        };
        msg.setFrom(address(transport.fromAddress(), transport.fromName()));
        if (transport.replyTo() != null && !transport.replyTo().isBlank()) {
            msg.setReplyTo(new InternetAddress[] {address(transport.replyTo(), transport.fromName())});
        }
        msg.setRecipient(MimeMessage.RecipientType.TO, address(q.recipient(), q.recipientName()));
        msg.setSubject(q.subject(), "UTF-8");
        msg.setSentDate(new java.util.Date());
        msg.setHeader("Auto-Submitted", "auto-generated");
        msg.setHeader("X-Auto-Response-Suppress", "All");
        msg.setHeader(LoopGuard.LOOP_HEADER, loopMark(q.tenantId()));
        if (q.ticketId() != null) {
            String root = tokens.threadRoot(q.tenantId(), q.ticketId(), transport.domain());
            msg.setHeader("In-Reply-To", root);
            msg.setHeader("References", root);
        }
        if (q.html() == null || q.html().isBlank()) {
            msg.setText(q.text(), "UTF-8", "plain");
        } else {
            MimeMultipart alt = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(q.text(), "UTF-8", "plain");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(q.html(), "UTF-8", "html");
            alt.addBodyPart(plain);
            alt.addBodyPart(html);
            msg.setContent(alt);
        }
        msg.saveChanges();
        return msg;
    }

    static byte[] bytes(MimeMessage msg) throws MessagingException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        msg.writeTo(out);
        return out.toByteArray();
    }

    /** Метка без раскрытия id арендатора: первые 16 знаков SHA-256. */
    static String loopMark(UUID tenantId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(tenantId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InternetAddress address(String email, String name) throws MessagingException {
        try {
            return name == null || name.isBlank() ? new InternetAddress(email, true)
                    : new InternetAddress(email, name, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Кодировка имени", e);
        }
    }
}
