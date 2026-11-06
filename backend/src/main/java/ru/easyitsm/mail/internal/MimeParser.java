package ru.easyitsm.mail.internal;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Component;

/** Разбор MIME входящего письма: заголовки, отправитель, текст без HTML и цитат, имена вложений. */
@Component
class MimeParser {

    private static final Session SESSION = Session.getInstance(new Properties());
    /** Ограничение текста, который попадёт в заявку: остальное — в исходном письме. */
    static final int MAX_TEXT = 60_000;

    ParsedMail parse(byte[] raw) throws MessagingException, IOException {
        MimeMessage msg = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<Header> all = msg.getAllHeaders();
        while (all.hasMoreElements()) {
            Header h = all.nextElement();
            headers.computeIfAbsent(h.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(unfold(h.getValue()));
        }
        String fromAddress = null;
        String fromName = null;
        Address[] from = safeFrom(msg);
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            fromAddress = ia.getAddress() == null ? null : ia.getAddress().trim().toLowerCase(Locale.ROOT);
            fromName = ia.getPersonal();
        }
        Body body = new Body();
        collect(msg, body);
        String text = body.plain != null ? body.plain : body.html != null ? MailText.htmlToText(body.html) : "";
        text = MailText.stripQuoted(MailText.normalize(text));
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT) + "\n…";
        }
        String subject;
        try {
            subject = msg.getSubject();
        } catch (MessagingException e) {
            subject = msg.getHeader("Subject", null);
        }
        String returnPath = first(headers, "return-path");
        boolean report = msg.isMimeType("multipart/report");
        subject = utf8(subject);
        fromName = utf8(fromName);
        return new ParsedMail(clean(msg.getMessageID()), first(headers, "in-reply-to"), first(headers, "references"),
                fromAddress, fromName, returnPath, subject == null ? "" : subject.strip(), text, headers,
                body.attachments, report, raw);
    }

    /** Заголовок с UTF-8 без кодирования RFC 2047 (SMTPUTF8) Jakarta Mail читает как ISO-8859-1: исправляем. */
    static String utf8(String s) {
        if (s == null || s.chars().noneMatch(c -> c >= 0x80 && c <= 0xff)) {
            return s;
        }
        String fixed = new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return fixed.contains("\uFFFD") ? s : fixed;
    }

    private static Address[] safeFrom(MimeMessage msg) {
        try {
            return msg.getFrom();
        } catch (MessagingException e) {
            return null;
        }
    }

    private static final class Body {
        String plain;
        String html;
        final List<ParsedMail.MailFile> attachments = new ArrayList<>();
    }

    private static void collect(Part part, Body body) throws MessagingException, IOException {
        String disposition = part.getDisposition();
        String filename = decodedFilename(part);
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || (filename != null && !part.isMimeType("multipart/*"))) {
            byte[] content;
            try (InputStream in = part.getInputStream()) {
                content = in.readAllBytes();
            }
            body.attachments.add(new ParsedMail.MailFile(filename == null ? "attachment" : filename,
                    baseType(part), content));
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                collect(bp, body);
            }
            return;
        }
        if (part.isMimeType("message/rfc822") || part.isMimeType("message/delivery-status")) {
            return;
        }
        if (part.isMimeType("text/plain") && body.plain == null) {
            body.plain = readText(part);
        } else if (part.isMimeType("text/html") && body.html == null) {
            body.html = readText(part);
        }
    }

    private static String readText(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof InputStream in) {
            try (in) {
                return new String(in.readNBytes(MAX_TEXT * 4), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String baseType(Part part) {
        try {
            String type = part.getContentType();
            if (type == null) {
                return "application/octet-stream";
            }
            int semi = type.indexOf(';');
            return (semi < 0 ? type : type.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
        } catch (MessagingException e) {
            return "application/octet-stream";
        }
    }

    private static String decodedFilename(Part part) {
        try {
            String name = part.getFileName();
            return name == null ? null : MimeUtility.decodeText(name);
        } catch (MessagingException | IOException e) {
            return "attachment";
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? null : v.getFirst();
    }

    private static String unfold(String value) {
        return value == null ? "" : value.replaceAll("\\r?\\n[ \\t]+", " ").trim();
    }

    private static String clean(String messageId) {
        return messageId == null || messageId.isBlank() ? null : messageId.trim();
    }
}
