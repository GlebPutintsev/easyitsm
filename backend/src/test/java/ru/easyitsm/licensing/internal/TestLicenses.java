package ru.easyitsm.licensing.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Выпуск лицензий в тестах тестовым ключом {@code test-2026} (src/test/resources/licensing). */
public final class TestLicenses {

    public static final String KID = "test-2026";

    private TestLicenses() {
    }

    public static String issue(String licenseId, int maxAgents, Instant notBefore, Instant notAfter) {
        return issue(licenseId, maxAgents, Instant.now(), notBefore, notAfter);
    }

    public static String issue(String licenseId, int maxAgents, Instant issuedAt, Instant notBefore, Instant notAfter) {
        String payload = """
                {"licenseId":"%s","licensee":"ООО Тест","issuedAt":"%s","notBefore":"%s","notAfter":"%s","maxAgents":%d,"features":[]}"""
                .formatted(licenseId, issuedAt, notBefore, notAfter, maxAgents);
        return envelope(KID, payload.getBytes(StandardCharsets.UTF_8), sign(testKey(), payload.getBytes(StandardCharsets.UTF_8)));
    }

    static String envelope(String kid, byte[] payload, byte[] signature) {
        Base64.Encoder b64 = Base64.getEncoder();
        return """
                {"format":"easyitsm-license/1","kid":"%s","payload":"%s","signature":"%s"}"""
                .formatted(kid, b64.encodeToString(payload), b64.encodeToString(signature));
    }

    static byte[] sign(PrivateKey key, byte[] payload) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(payload);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivateKey testKey() {
        String pem = resource("licensing/test-2026.key");
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static PrivateKey foreignKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String resource(String path) {
        try (InputStream in = TestLicenses.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Нет ресурса " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
