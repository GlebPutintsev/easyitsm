package ru.easyitsm.notifications.internal;

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
 * Шифрование закрытого ключа VAPID (AES-256-GCM). Ключ — {@code easyitsm.notifications.secret-key}, иначе
 * {@code easyitsm.identity.secret-key}; для шифрования выводится отдельный подключ.
 */
@Component
class KeyBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    KeyBox(Environment env) {
        String raw = env.getProperty("easyitsm.notifications.secret-key", "");
        if (raw.isBlank()) {
            raw = env.getProperty("easyitsm.identity.secret-key", "");
        }
        byte[] master;
        if (raw.isBlank()) {
            master = new byte[32];
            RANDOM.nextBytes(master);
        } else {
            try {
                master = MessageDigest.getInstance("SHA-256").digest(raw.trim().getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            key = mac.doFinal("easyitsm-push-vapid".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return "v1:" + Base64.getEncoder().encodeToString(WebPush.concat(iv, c.doFinal(plain)));
    }

    byte[] decrypt(String stored) throws GeneralSecurityException {
        byte[] in = Base64.getDecoder().decode(stored.substring(3));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
        return c.doFinal(in, 12, in.length - 12);
    }
}
