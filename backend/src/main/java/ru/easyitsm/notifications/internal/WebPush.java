package ru.easyitsm.notifications.internal;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push без сторонних библиотек: шифрование содержимого aes128gcm (RFC 8291, RFC 8188) и подпись VAPID
 * (RFC 8292, ES256). Всё на стандартной криптографии JDK.
 */
final class WebPush {

    private static final SecureRandom RANDOM = new SecureRandom();
    static final ECParameterSpec P256;

    static {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            P256 = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WebPush() {
    }

    static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return g.generateKeyPair();
    }

    /** Открытый ключ в несжатом виде (65 байт, 0x04 || X || Y). */
    static byte[] raw(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1);
        copy32(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECPublicKey fromRaw(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Неверный открытый ключ P-256");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /**
     * Шифрует содержимое для подписки браузера.
     *
     * @param uaPublic открытый ключ подписки (p256dh), 65 байт
     * @param auth     секрет подписки (auth), 16 байт
     */
    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] auth) throws GeneralSecurityException {
        KeyPair as = newKeyPair();
        byte[] asPublic = raw((ECPublicKey) as.getPublic());
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, uaPublic, auth, as, asPublic, salt);
    }

    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] auth, KeyPair as, byte[] asPublic, byte[] salt)
            throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(fromRaw(uaPublic), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkKey = hmac(auth, ecdh);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[] {1});
        byte[] ikm = hmac(prkKey, keyInfo);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ct = c.doFinal(concat(payload, new byte[] {2}));

        ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ct.length);
        out.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic).put(ct);
        return out.array();
    }

    /** Заголовок Authorization по схеме vapid (RFC 8292). */
    static String vapidAuthorization(String endpoint, String subject, PrivateKey key, byte[] publicRaw)
            throws GeneralSecurityException {
        java.net.URI uri = java.net.URI.create(endpoint);
        String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        long exp = System.currentTimeMillis() / 1000 + 12 * 3600;
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + audience + "\",\"exp\":" + exp + ",\"sub\":\"" + subject.replace("\"", "")
                + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(key);
        s.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String jwt = header + "." + claims + "." + b64(s.sign());
        return "vapid t=" + jwt + ", k=" + b64(publicRaw);
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    private static void copy32(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, offset + 32 - len, len);
    }
}
