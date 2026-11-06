package ru.easyitsm.cmdb.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** Токены источников CMDB: 256 случайных бит, в БД — только SHA-256. */
final class SourceTokens {

    static final String PREFIX = "eitsm_cmdb_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SourceTokens() {
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

    /** Последние символы для списка источников: по ним видно, какой токен настроен у агента. */
    static String hint(String token) {
        return "…" + token.substring(token.length() - 4);
    }

    /** Токен из {@code Authorization: Bearer …}; только токены источников CMDB. */
    static Optional<String> bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).strip();
        return token.startsWith(PREFIX) && token.length() <= 200 ? Optional.of(token) : Optional.empty();
    }
}
