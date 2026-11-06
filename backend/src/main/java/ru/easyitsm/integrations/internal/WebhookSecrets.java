package ru.easyitsm.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Шифрование ключей подписи вебхуков (AES-256-GCM, раздел 8.5: секрет не выводится после сохранения).
 * Ключ — {@code easyitsm.integrations.secret-key}, иначе {@code easyitsm.identity.secret-key}; из него выводится
 * отдельный подключ. Ключ должен быть одинаков у api и worker: worker расшифровывает при отправке.
 */
@Component
class WebhookSecrets {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    WebhookSecrets(Environment env) {
        String raw = env.getProperty("easyitsm.integrations.secret-key", "");
        if (raw.isBlank()) {
            raw = env.getProperty("easyitsm.identity.secret-key", "");
        }
        byte[] master = raw.isBlank() ? random(32) : decode(raw);
        this.key = derive(master, "easyitsm-integrations-encryption");
    }

    String encrypt(String plain) {
        try {
            byte[] iv = random(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    /** null — секрет не расшифровывается (сменился ключ): получателю нужен новый ключ подписи. */
    String decryptOrNull(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return null;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    static byte[] hmac(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String raw) {
        try {
            byte[] k = Base64.getDecoder().decode(raw.trim());
            if (k.length >= 16) {
                return k;
            }
        } catch (IllegalArgumentException ignored) {
            // не Base64 — парольная фраза
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
