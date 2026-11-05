package ru.easyitsm.licensing.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Разбор и проверка файла лицензии. Формат {@code easyitsm-license/1}:
 * <pre>
 * {"format":"easyitsm-license/1","kid":"vendor-2026","payload":"&lt;base64&gt;","signature":"&lt;base64&gt;"}
 * </pre>
 * {@code payload} — JSON с полями лицензии в base64, {@code signature} — подпись Ed25519 над байтами payload
 * после декодирования. Подписываются ровно те байты, что лежат в файле, поэтому канонизация JSON не нужна,
 * и файл можно подписать стандартным {@code openssl pkeyutl -sign -rawin}.
 */
final class LicenseCodec {

    static final String FORMAT = "easyitsm-license/1";
    static final int MAX_SIZE = 64 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TrustedKeys keys;

    LicenseCodec(TrustedKeys keys) {
        this.keys = keys;
    }

    VerifiedLicense verify(String file) throws LicenseException {
        if (file == null || file.isBlank()) {
            throw new LicenseException("license_empty", "Файл лицензии пуст");
        }
        if (file.length() > MAX_SIZE) {
            throw new LicenseException("license_too_large", "Файл лицензии больше 64 КБ");
        }
        JsonNode envelope = parse(file);
        if (!FORMAT.equals(text(envelope, "format"))) {
            throw new LicenseException("license_format", "Неизвестный формат файла лицензии");
        }
        String keyId = required(envelope, "kid");
        PublicKey key = keys.find(keyId)
                .orElseThrow(() -> new LicenseException("license_unknown_key", "Лицензия подписана неизвестным ключом"));
        byte[] payload = decode(required(envelope, "payload"));
        byte[] signature = decode(required(envelope, "signature"));
        if (!signatureValid(key, payload, signature)) {
            throw new LicenseException("license_bad_signature", "Подпись лицензии не сходится");
        }
        return readPayload(parse(new String(payload, StandardCharsets.UTF_8)), keyId);
    }

    private static VerifiedLicense readPayload(JsonNode p, String keyId) throws LicenseException {
        Instant notBefore = instant(p, "notBefore");
        Instant notAfter = instant(p, "notAfter");
        if (!notAfter.isAfter(notBefore)) {
            throw new LicenseException("license_payload", "Окончание действия лицензии раньше начала");
        }
        JsonNode agents = p.get("maxAgents");
        if (agents == null || !agents.canConvertToInt() || agents.asInt() < 1) {
            throw new LicenseException("license_payload", "В лицензии нет лимита агентов");
        }
        List<String> features = new ArrayList<>();
        JsonNode f = p.get("features");
        if (f != null && f.isArray()) {
            f.forEach(n -> features.add(n.asString()));
        }
        return new VerifiedLicense(required(p, "licenseId"), required(p, "licensee"), instant(p, "issuedAt"),
                notBefore, notAfter, agents.asInt(), List.copyOf(features), keyId);
    }

    private static boolean signatureValid(PublicKey key, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static JsonNode parse(String json) throws LicenseException {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                throw new LicenseException("license_format", "Файл лицензии не является объектом JSON");
            }
            return node;
        } catch (JacksonException e) {
            throw new LicenseException("license_format", "Файл лицензии не является JSON");
        }
    }

    private static byte[] decode(String base64) throws LicenseException {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new LicenseException("license_format", "Повреждённые данные в файле лицензии");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static String required(JsonNode node, String field) throws LicenseException {
        String v = text(node, field);
        if (v == null || v.isBlank()) {
            throw new LicenseException("license_payload", "В лицензии нет поля " + field);
        }
        return v;
    }

    private static Instant instant(JsonNode node, String field) throws LicenseException {
        try {
            return Instant.parse(required(node, field));
        } catch (DateTimeParseException e) {
            throw new LicenseException("license_payload", "Неверная дата в поле " + field);
        }
    }
}
