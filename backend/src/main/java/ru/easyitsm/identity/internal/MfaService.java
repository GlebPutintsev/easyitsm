package ru.easyitsm.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/** Факторы MFA (SEC-02): TOTP, ключи WebAuthn, резервные коды и «запомненные» устройства. */
@Service
class MfaService {

    static final String TOTP = "totp";
    static final String WEBAUTHN = "webauthn";
    static final String RECOVERY = "recovery";

    private final Credentials credentials;
    private final Secrets secrets;
    private final JdbcClient jdbc;
    private final IdentityProperties props;

    MfaService(Credentials credentials, Secrets secrets, JdbcClient jdbc, IdentityProperties props) {
        this.credentials = credentials;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.props = props;
    }

    record TotpSetup(String secret, String otpauthUri) {
    }

    /** Подключённые факторы пользователя в порядке предпочтения: ключ, приложение, резервный код. */
    List<String> factors(UUID userId) {
        List<String> factors = new ArrayList<>();
        if (webauthnCount(userId) > 0) {
            factors.add(WEBAUTHN);
        }
        var row = credentials.require(userId);
        if (row.totpEnabled()) {
            factors.add(TOTP);
        }
        if (!factors.isEmpty() && credentials.recoveryCodesLeft(userId) > 0) {
            factors.add(RECOVERY);
        }
        return factors;
    }

    boolean mfaRequired(java.util.Set<Role> roles) {
        if (!props.mfa().enforced()) {
            return false;
        }
        return props.mfa().requiredForAll() || roles.stream().anyMatch(Role::requiresMfa);
    }

    int webauthnCount(UUID userId) {
        return jdbc.sql("select count(*) from webauthn_credential where user_id = :user")
                .param("user", userId).query(Integer.class).single();
    }

    /** Новый секрет TOTP. До подтверждения кодом он не действует, повторный вызов выдаёт новый. */
    TotpSetup startTotp(UUID userId, String account) {
        if (credentials.require(userId).totpEnabled()) {
            throw ApiException.conflict("totp_already_enabled", "Приложение-аутентификатор уже подключено");
        }
        byte[] secret = Secrets.randomBytes(Totp.SECRET_BYTES);
        credentials.setPendingTotp(userId, secrets.encrypt(secret, Credentials.TOTP_CONTEXT + userId));
        return new TotpSetup(Totp.base32(secret), Totp.otpauthUri(props.mfa().issuer(), account, secret));
    }

    /** Подтверждает TOTP первым кодом. Если это первый фактор, выдаёт резервные коды. */
    List<String> confirmTotp(UUID userId, String code) {
        var row = credentials.require(userId);
        if (row.totpEnabled()) {
            throw ApiException.conflict("totp_already_enabled", "Приложение-аутентификатор уже подключено");
        }
        if (row.totpSecret() == null) {
            throw ApiException.badRequest("totp_not_started", "Сначала получите секрет для приложения");
        }
        byte[] secret = secrets.decrypt(row.totpSecret(), Credentials.TOTP_CONTEXT + userId);
        OptionalLong step = Totp.verify(secret, code, Instant.now(), null);
        if (step.isEmpty()) {
            throw AuthFailure.invalidCode(0);
        }
        credentials.confirmTotp(userId, step.getAsLong());
        return credentials.recoveryCodesLeft(userId) == 0 ? credentials.replaceRecoveryCodes(userId) : List.of();
    }

    boolean verifyTotp(UUID userId, String code) {
        var row = credentials.require(userId);
        if (!row.totpEnabled()) {
            return false;
        }
        byte[] secret = secrets.decrypt(row.totpSecret(), Credentials.TOTP_CONTEXT + userId);
        OptionalLong step = Totp.verify(secret, code, Instant.now(), row.totpLastStep());
        return step.isPresent() && credentials.useTotpStep(userId, step.getAsLong());
    }

    boolean verifyRecovery(UUID userId, String code) {
        return credentials.useRecoveryCode(userId, code);
    }

    void disableTotp(UUID userId) {
        credentials.clearTotp(userId);
        dropRecoveryIfNoFactors(userId);
    }

    void dropRecoveryIfNoFactors(UUID userId) {
        var row = credentials.require(userId);
        if (!row.totpEnabled() && webauthnCount(userId) == 0) {
            credentials.clearRecoveryCodes(userId);
        }
    }

    List<String> regenerateRecoveryCodes(UUID userId) {
        if (!credentials.require(userId).totpEnabled() && webauthnCount(userId) == 0) {
            throw ApiException.conflict("no_mfa", "Сначала подключите приложение или ключ");
        }
        return credentials.replaceRecoveryCodes(userId);
    }

    // «Запомнить устройство» на N дней, если политика разрешает.

    boolean rememberDeviceAllowed() {
        return props.mfa().rememberDeviceDays() > 0;
    }

    Duration rememberDuration() {
        return Duration.ofDays(props.mfa().rememberDeviceDays());
    }

    String rememberDevice(UUID userId, RequestInfo info) {
        String token = Secrets.newToken();
        jdbc.sql("""
                insert into trusted_device (id, tenant_id, user_id, token_hash, user_agent, expires_at)
                values (:id, :tenant, :user, :hash, :ua, :expires)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("user", userId)
                .param("hash", Secrets.sha256(token)).param("ua", info.userAgent())
                .param("expires", java.sql.Timestamp.from(Instant.now().plus(rememberDuration())))
                .update();
        return token;
    }

    boolean isTrustedDevice(UUID userId, String token) {
        if (!rememberDeviceAllowed() || token == null) {
            return false;
        }
        return jdbc.sql("""
                select exists (select 1 from trusted_device
                                where user_id = :user and token_hash = :hash and expires_at > now())""")
                .param("user", userId).param("hash", Secrets.sha256(token)).query(Boolean.class).single();
    }

    void forgetDevices(UUID userId) {
        jdbc.sql("delete from trusted_device where user_id = :user").param("user", userId).update();
    }
}
