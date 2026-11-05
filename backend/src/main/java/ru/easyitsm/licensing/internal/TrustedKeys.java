package ru.easyitsm.licensing.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Открытые ключи вендора Ed25519, которыми подписаны лицензии. Лежат в {@code classpath:licensing/keys/<kid>.pub}
 * (PEM, SubjectPublicKeyInfo). Источник только classpath: ключ нельзя подменить настройкой или переменной окружения.
 * Несколько ключей — для плановой смены ключа: старые лицензии остаются действительными.
 */
final class TrustedKeys {

    static final String LOCATION = "classpath*:licensing/keys/*.pub";

    private final Map<String, PublicKey> keys;

    TrustedKeys(Map<String, PublicKey> keys) {
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
    }

    static TrustedKeys loadFromClasspath() {
        try {
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                String name = r.getFilename();
                if (name == null) {
                    continue;
                }
                try (InputStream in = r.getInputStream()) {
                    keys.put(name.substring(0, name.length() - ".pub".length()),
                            parsePem(new String(in.readAllBytes(), StandardCharsets.US_ASCII)));
                }
            }
            return new TrustedKeys(keys);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать ключи лицензий", e);
        }
    }

    static PublicKey parsePem(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new IllegalStateException("Ключ лицензий не в формате Ed25519 PEM", e);
        }
    }

    Optional<PublicKey> find(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    int size() {
        return keys.size();
    }
}
