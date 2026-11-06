package ru.easyitsm.mail.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Токен ответа (поток 6.4, шаг 3): связывает ответное письмо с заявкой. Токен — HMAC от арендатора и заявки,
 * подделать его, зная только номер заявки, нельзя. Номер в теме письма сам по себе заявку не выбирает.
 * <p>
 * Токен попадает в письмо дважды: в Message-ID и References (почтовые клиенты возвращают их в In-Reply-To и
 * References ответа) и в строке {@code [ref:INC-000123:токен]} в конце текста — на случай, если клиент
 * заголовки потерял.
 */
@Component
class ReplyTokens {

    static final Pattern HEADER_REF = Pattern.compile("eitsm\\.([0-9a-f]{32})\\.([a-z2-7]{20})");
    static final Pattern BODY_REF = Pattern.compile("\\[ref:([A-Z][A-Z0-9]{1,9}-\\d{1,12}):([a-z2-7]{20})]");
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private final SecretBox secrets;

    ReplyTokens(SecretBox secrets) {
        this.secrets = secrets;
    }

    String token(UUID tenantId, UUID ticketId) {
        byte[] mac = secrets.hmac("reply|" + tenantId + "|" + ticketId);
        StringBuilder sb = new StringBuilder(20);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; sb.length() < 20; i++) {
            buffer = (buffer << 8) | (mac[i] & 0xff);
            bits += 8;
            while (bits >= 5 && sb.length() < 20) {
                sb.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return sb.toString();
    }

    boolean verify(UUID tenantId, UUID ticketId, String token) {
        return token != null && MessageDigest.isEqual(token(tenantId, ticketId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    /** Корень цепочки писем заявки: одинаковый для всех писем, чтобы клиенты собрали их в одну беседу. */
    String threadRoot(UUID tenantId, UUID ticketId, String domain) {
        return "<eitsm." + hex(ticketId) + "." + token(tenantId, ticketId) + "@" + domain + ">";
    }

    String messageId(UUID tenantId, UUID ticketId, UUID deliveryId, String domain) {
        if (ticketId == null) {
            return "<eitsm.m." + hex(deliveryId) + "@" + domain + ">";
        }
        return "<eitsm." + hex(ticketId) + "." + token(tenantId, ticketId) + "." + hex(deliveryId) + "@" + domain + ">";
    }

    String bodyRef(UUID tenantId, UUID ticketId, String number) {
        return "[ref:" + number + ":" + token(tenantId, ticketId) + "]";
    }

    /** Заявка из заголовков In-Reply-To и References, если токен верный. */
    Optional<UUID> fromHeaders(UUID tenantId, String headers) {
        if (headers == null) {
            return Optional.empty();
        }
        Matcher m = HEADER_REF.matcher(headers);
        while (m.find()) {
            UUID ticketId = fromHex(m.group(1));
            if (ticketId != null && verify(tenantId, ticketId, m.group(2))) {
                return Optional.of(ticketId);
            }
        }
        return Optional.empty();
    }

    /** Пары (номер, токен) из текста или темы; проверка токена — после поиска заявки по номеру. */
    Optional<BodyRef> fromText(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = BODY_REF.matcher(text);
        return m.find() ? Optional.of(new BodyRef(m.group(1), m.group(2))) : Optional.empty();
    }

    record BodyRef(String number, String token) {
    }

    static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    static UUID fromHex(String hex) {
        try {
            return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
