package ru.easyitsm.identity.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** TOTP по RFC 6238: HMAC-SHA1, 6 цифр, шаг 30 секунд, допуск ±1 шаг на рассинхронизацию часов. */
final class Totp {

    static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Проверяет код и возвращает шаг, на котором он совпал. Шаг не больше {@code lastUsedStep} не принимается:
     * один и тот же код нельзя использовать дважды.
     */
    static OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long current = stepAt(now);
        for (long step = current - 1; step <= current + 1; step++) {
            if (lastUsedStep != null && step <= lastUsedStep) {
                continue;
            }
            if (java.security.MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
