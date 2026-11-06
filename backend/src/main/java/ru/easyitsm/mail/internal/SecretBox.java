package ru.easyitsm.mail.internal;

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
 * Шифрование паролей почтовых ящиков (AES-256-GCM) и ключ подписи токенов ответа.
 * Ключ — {@code easyitsm.mail.secret-key}, иначе {@code easyitsm.identity.secret-key}; из него выводятся
 * отдельные подключи для шифрования и подписи, чтобы один и тот же ключ не использовался для двух целей.
 */
@Component
class SecretBox {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] encryptionKey;
    private final byte[] signingKey;

    SecretBox(MailProperties props, Environment env) {
        String raw = props.secretKey();
        if (raw == null || raw.isBlank()) {
            raw = env.getProperty("easyitsm.identity.secret-key", "");
        }
        byte[] master = raw.isBlank()
                // Без ключа (тесты срезов, первая загрузка) — ключ процесса: секреты не переживут перезапуск,
                // но и не окажутся в БД открытыми.
                ? randomBytes(32)
                : decodeKey(raw);
        this.encryptionKey = derive(master, "easyitsm-mail-encryption");
        this.signingKey = derive(master, "easyitsm-mail-reply-token");
    }

    String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = randomBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Неизвестный формат секрета");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Секрет не расшифровывается: сменился ключ easyitsm.mail.secret-key?", e);
        }
    }

    /** Как {@link #decrypt}, но при смене ключа — null: настройку можно открыть и задать пароль заново. */
    String decryptOrNull(String stored) {
        try {
            return decrypt(stored);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
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

    private static byte[] decodeKey(String raw) {
        try {
            byte[] key = Base64.getDecoder().decode(raw.trim());
            if (key.length >= 16) {
                return key;
            }
        } catch (IllegalArgumentException ignored) {
            // не Base64 — используем как парольную фразу
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
