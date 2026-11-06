package ru.easyitsm.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Токены источников: 256 случайных бит, в БД — только SHA-256. */
final class Tokens {

    static final String PREFIX = "eitsm_mon_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    static String generate() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.strip().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Последние символы для списка источников: администратор узнаёт, какой токен настроен. */
    static String hint(String token) {
        return "…" + token.substring(token.length() - 4);
    }
}
