package ru.easyitsm.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Случайные токены, их хеши и шифрование секретов ключом приложения (AES-256-GCM).
 * Токены сессий и ссылок хранятся только как SHA-256: у них 256 бит энтропии, соль не нужна.
 */
final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec key;

    Secrets(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("Ключ easyitsm.identity.secret-key должен быть 32 байта в base64");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** Токен для куки или ссылки: 32 случайных байта в base64url. */
    static String newToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    static byte[] sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    byte[] encrypt(byte[] plain, String context) {
        try {
            byte[] iv = randomBytes(IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_LENGTH + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    /** @throws IllegalArgumentException если данные подделаны или зашифрованы другим ключом */
    byte[] decrypt(byte[] sealed, String context) {
        if (sealed == null || sealed.length <= IV_LENGTH) {
            throw new IllegalArgumentException("Некорректный шифротекст");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(sealed, IV_LENGTH)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, IV_LENGTH, sealed.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Шифротекст не прошёл проверку", e);
        }
    }
}
