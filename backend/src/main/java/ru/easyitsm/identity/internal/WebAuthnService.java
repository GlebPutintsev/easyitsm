package ru.easyitsm.identity.internal;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Ключи WebAuthn и passkeys (SEC-02): регистрация, второй фактор и вход без пароля.
 * Параметры вызова отдаются в JSON-формате WebAuthn Level 3, который понимает {@code @simplewebauthn/browser}.
 */
@Service
class WebAuthnService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);
    private static final Duration CEREMONY_TTL = Duration.ofMinutes(5);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final WebAuthnManager manager;
    private final AttestedCredentialDataConverter attestedConverter =
            new AttestedCredentialDataConverter(new ObjectConverter());
    private final JdbcClient jdbc;
    private final IdentityProperties.Webauthn props;
    private final Set<Origin> origins;

    WebAuthnService(WebAuthnManager manager, JdbcClient jdbc, IdentityProperties props) {
        this.manager = manager;
        this.jdbc = jdbc;
        this.props = props.webauthn();
        this.origins = this.props.origins().stream().map(Origin::new).collect(Collectors.toSet());
    }

    record Options(UUID ceremonyId, Map<String, Object> publicKey) {
    }

    record KeyView(UUID id, String name, Instant createdAt, Instant lastUsedAt, boolean passkey) {
    }

    List<KeyView> list(UUID userId) {
        return jdbc.sql("""
                select id, name, created_at, last_used_at, backup_eligible from webauthn_credential
                 where user_id = :user order by created_at""")
                .param("user", userId)
                .query((rs, n) -> new KeyView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), ts(rs.getTimestamp(4)), rs.getBoolean(5)))
                .list();
    }

    boolean delete(UUID userId, UUID keyId) {
        return jdbc.sql("delete from webauthn_credential where id = :id and user_id = :user")
                .param("id", keyId).param("user", userId).update() == 1;
    }

    Options registrationOptions(UUID userId, String login, String displayName) {
        byte[] challenge = Secrets.randomBytes(32);
        UUID ceremony = saveCeremony(userId, "register", challenge);
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("challenge", B64.encodeToString(challenge));
        pk.put("rp", Map.of("id", props.rpId(), "name", props.rpName()));
        pk.put("user", Map.of("id", B64.encodeToString(userHandle(userId)), "name", login, "displayName", displayName));
        pk.put("pubKeyCredParams", List.of(
                Map.of("type", "public-key", "alg", -7),     // ES256
                Map.of("type", "public-key", "alg", -8),     // EdDSA
                Map.of("type", "public-key", "alg", -257))); // RS256
        pk.put("timeout", CEREMONY_TTL.toMillis());
        pk.put("attestation", "none");
        pk.put("authenticatorSelection", Map.of("residentKey", "preferred", "userVerification", "preferred"));
        pk.put("excludeCredentials", credentialDescriptors(userId));
        return new Options(ceremony, pk);
    }

    UUID register(UUID userId, UUID ceremonyId, String responseJson, String name) {
        byte[] challenge = consumeCeremony(ceremonyId, "register", userId);
        RegistrationData data;
        try {
            var params = new RegistrationParameters(new ServerProperty(origins, props.rpId(),
                    new DefaultChallenge(challenge)), null, false, true);
            data = manager.verifyRegistrationResponseJSON(responseJson, params);
        } catch (RuntimeException e) {
            log.info("Регистрация ключа WebAuthn отклонена: {}", e.getClass().getSimpleName());
            throw ApiException.badRequest("webauthn_invalid", "Не удалось проверить ключ. Попробуйте ещё раз");
        }
        var authData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attested = authData.getAttestedCredentialData();
        UUID id = Ids.newId();
        Duplicates.guard(() -> jdbc.sql("""
                insert into webauthn_credential (id, tenant_id, user_id, credential_id, attested_data, sign_count,
                                                 uv_initialized, backup_eligible, backup_state, name)
                values (:id, :tenant, :user, :cid, :data, :count, :uv, :be, :bs, :name)""")
                .param("id", id).param("tenant", TenantContext.require()).param("user", userId)
                .param("cid", attested.getCredentialId()).param("data", attestedConverter.convert(attested))
                .param("count", authData.getSignCount()).param("uv", authData.isFlagUV())
                .param("be", authData.isFlagBE()).param("bs", authData.isFlagBS())
                .param("name", name == null || name.isBlank() ? "Ключ" : name.trim())
                .update(), "key_exists", "Этот ключ уже добавлен");
        return id;
    }

    /**
     * Параметры подтверждения ключом.
     *
     * @param userId пользователь для второго фактора; null — вход по passkey без логина
     */
    Options authenticationOptions(UUID userId) {
        byte[] challenge = Secrets.randomBytes(32);
        UUID ceremony = saveCeremony(userId, "authenticate", challenge);
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("challenge", B64.encodeToString(challenge));
        pk.put("rpId", props.rpId());
        pk.put("timeout", CEREMONY_TTL.toMillis());
        pk.put("userVerification", userId == null ? "required" : "preferred");
        pk.put("allowCredentials", userId == null ? List.of() : credentialDescriptors(userId));
        return new Options(ceremony, pk);
    }

    /**
     * Проверяет подпись ключа.
     *
     * @param expectedUserId пользователь второго фактора или null для passkey
     * @return владелец ключа
     */
    UUID authenticate(UUID ceremonyId, String responseJson, UUID expectedUserId) {
        byte[] challenge = consumeCeremony(ceremonyId, "authenticate", expectedUserId);
        AuthenticationData parsed;
        try {
            parsed = manager.parseAuthenticationResponseJSON(responseJson);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("webauthn_invalid", "Не удалось проверить ключ");
        }
        var stored = jdbc.sql("""
                select id, user_id, attested_data, sign_count, uv_initialized, backup_eligible, backup_state
                  from webauthn_credential where credential_id = :cid""")
                .param("cid", parsed.getCredentialId())
                .query((rs, n) -> new StoredKey(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getBytes(3), rs.getLong(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)))
                .optional()
                .orElseThrow(() -> new AuthFailure(0));
        if (expectedUserId != null && !expectedUserId.equals(stored.userId())) {
            throw new AuthFailure(0);
        }
        if (parsed.getUserHandle() != null && parsed.getUserHandle().length > 0
                && !java.util.Arrays.equals(parsed.getUserHandle(), userHandle(stored.userId()))) {
            throw new AuthFailure(0);
        }
        var record = new CredentialRecordImpl(new NoneAttestationStatement(), stored.uv(), stored.be(), stored.bs(),
                stored.signCount(), attestedConverter.convert(stored.attested()), null, null, null, null);
        try {
            var params = new AuthenticationParameters(new ServerProperty(origins, props.rpId(),
                    new DefaultChallenge(challenge)), record, null, expectedUserId == null, true);
            manager.verify(parsed, params);
        } catch (RuntimeException e) {
            log.info("Подпись WebAuthn отклонена: {}", e.getClass().getSimpleName());
            throw new AuthFailure(0);
        }
        var authData = parsed.getAuthenticatorData();
        jdbc.sql("""
                update webauthn_credential set sign_count = :count, backup_state = :bs, last_used_at = now()
                 where id = :id""")
                .param("count", authData.getSignCount()).param("bs", authData.isFlagBS()).param("id", stored.id())
                .update();
        return stored.userId();
    }

    private record StoredKey(UUID id, UUID userId, byte[] attested, long signCount, boolean uv, boolean be,
                             boolean bs) {
    }

    private List<Map<String, Object>> credentialDescriptors(UUID userId) {
        return jdbc.sql("select credential_id from webauthn_credential where user_id = :user")
                .param("user", userId).query(byte[].class).list().stream()
                .map(id -> Map.<String, Object>of("type", "public-key", "id", B64.encodeToString(id)))
                .toList();
    }

    private UUID saveCeremony(UUID userId, String purpose, byte[] challenge) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into webauthn_ceremony (id, tenant_id, user_id, purpose, challenge, expires_at)
                values (:id, :tenant, :user, :purpose, :challenge, :expires)""")
                .param("id", id).param("tenant", TenantContext.require()).param("user", userId)
                .param("purpose", purpose).param("challenge", challenge)
                .param("expires", Timestamp.from(Instant.now().plus(CEREMONY_TTL)))
                .update();
        return id;
    }

    /** Вызов одноразовый: удаляется при первом использовании, даже неудачном. */
    private byte[] consumeCeremony(UUID ceremonyId, String purpose, UUID userId) {
        if (ceremonyId == null) {
            throw ApiException.badRequest("webauthn_expired", "Время на подтверждение истекло. Повторите");
        }
        return jdbc.sql("""
                delete from webauthn_ceremony
                 where id = :id and purpose = :purpose and expires_at > now()
                   and user_id is not distinct from cast(:user as uuid)
                returning challenge""")
                .param("id", ceremonyId).param("purpose", purpose).param("user", userId)
                .query(byte[].class).optional()
                .orElseThrow(() -> ApiException.badRequest("webauthn_expired",
                        "Время на подтверждение истекло. Повторите"));
    }

    static byte[] userHandle(UUID userId) {
        return ByteBuffer.allocate(16).putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits()).array();
    }

    private static Instant ts(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
