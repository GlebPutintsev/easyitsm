package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** Учётные данные: хеш пароля, счётчик неудачных попыток, секрет TOTP, резервные коды. Работает под RLS. */
@Repository
class Credentials {

    static final String TOTP_CONTEXT = "totp";

    private final JdbcClient jdbc;

    Credentials(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(UUID userId, String passwordHash, Instant passwordChangedAt, int failedAttempts, Instant lockedUntil,
               byte[] totpSecret, Instant totpConfirmedAt, Long totpLastStep) {

        boolean locked(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        boolean totpEnabled() {
            return totpSecret != null && totpConfirmedAt != null;
        }
    }

    void create(UUID userId, String passwordHash) {
        jdbc.sql("""
                insert into user_credential (user_id, tenant_id, password_hash, password_changed_at)
                values (:user, :tenant, :hash, case when cast(:hash as text) is null then null else now() end)
                on conflict (user_id) do nothing""")
                .param("user", userId).param("tenant", TenantContext.require()).param("hash", passwordHash)
                .update();
    }

    Optional<Row> find(UUID userId) {
        return jdbc.sql("""
                select user_id, password_hash, password_changed_at, failed_attempts, locked_until,
                       totp_secret, totp_confirmed_at, totp_last_step
                  from user_credential where user_id = :user""")
                .param("user", userId)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), ts(rs.getTimestamp(3)),
                        rs.getInt(4), ts(rs.getTimestamp(5)), rs.getBytes(6), ts(rs.getTimestamp(7)),
                        (Long) rs.getObject(8)))
                .optional();
    }

    /** Строка для пользователя без неё (созданного до identity или через SSO). */
    Row require(UUID userId) {
        return find(userId).orElseGet(() -> {
            create(userId, null);
            return find(userId).orElseThrow();
        });
    }

    void setPassword(UUID userId, String hash) {
        require(userId);
        jdbc.sql("""
                update user_credential set password_hash = :hash, password_changed_at = now(),
                       failed_attempts = 0, locked_until = null, updated_at = now()
                 where user_id = :user""")
                .param("hash", hash).param("user", userId).update();
    }

    /** Обновляет хеш при входе, если параметры Argon2 усилены; дату смены пароля не трогает. */
    void rehash(UUID userId, String hash) {
        jdbc.sql("update user_credential set password_hash = :hash where user_id = :user")
                .param("hash", hash).param("user", userId).update();
    }

    /** @return число неудачных попыток подряд после этой */
    int registerFailure(UUID userId, int maxFailures, Instant lockUntil) {
        require(userId);
        return jdbc.sql("""
                update user_credential
                   set failed_attempts = failed_attempts + 1,
                       locked_until = case when failed_attempts + 1 >= :max then :lockUntil else locked_until end,
                       updated_at = now()
                 where user_id = :user
                returning failed_attempts""")
                .param("max", maxFailures).param("lockUntil", java.sql.Timestamp.from(lockUntil)).param("user", userId)
                .query(Integer.class).single();
    }

    void resetFailures(UUID userId) {
        jdbc.sql("update user_credential set failed_attempts = 0, locked_until = null where user_id = :user and failed_attempts > 0")
                .param("user", userId).update();
    }

    void unlock(UUID userId) {
        resetFailures(userId);
    }

    void setPendingTotp(UUID userId, byte[] encryptedSecret) {
        require(userId);
        jdbc.sql("""
                update user_credential set totp_secret = :secret, totp_confirmed_at = null, totp_last_step = null,
                       updated_at = now()
                 where user_id = :user and totp_confirmed_at is null""")
                .param("secret", encryptedSecret).param("user", userId).update();
    }

    void confirmTotp(UUID userId, long step) {
        jdbc.sql("update user_credential set totp_confirmed_at = now(), totp_last_step = :step where user_id = :user")
                .param("step", step).param("user", userId).update();
    }

    /** Запоминает использованный шаг. Условие защищает от повторного использования кода при гонке. */
    boolean useTotpStep(UUID userId, long step) {
        return jdbc.sql("""
                update user_credential set totp_last_step = :step
                 where user_id = :user and (totp_last_step is null or totp_last_step < :step)""")
                .param("step", step).param("user", userId).update() == 1;
    }

    void clearTotp(UUID userId) {
        jdbc.sql("""
                update user_credential set totp_secret = null, totp_confirmed_at = null, totp_last_step = null,
                       updated_at = now()
                 where user_id = :user""")
                .param("user", userId).update();
    }

    List<String> replaceRecoveryCodes(UUID userId) {
        jdbc.sql("delete from recovery_code where user_id = :user").param("user", userId).update();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = RecoveryCodes.generate();
            codes.add(code);
            jdbc.sql("insert into recovery_code (id, tenant_id, user_id, code_hash) values (:id, :tenant, :user, :hash)")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("user", userId)
                    .param("hash", Secrets.sha256(RecoveryCodes.normalize(code)))
                    .update();
        }
        return codes;
    }

    boolean useRecoveryCode(UUID userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return jdbc.sql("""
                update recovery_code set used_at = now()
                 where user_id = :user and code_hash = :hash and used_at is null""")
                .param("user", userId).param("hash", Secrets.sha256(RecoveryCodes.normalize(code)))
                .update() == 1;
    }

    int recoveryCodesLeft(UUID userId) {
        return jdbc.sql("select count(*) from recovery_code where user_id = :user and used_at is null")
                .param("user", userId).query(Integer.class).single();
    }

    void clearRecoveryCodes(UUID userId) {
        jdbc.sql("delete from recovery_code where user_id = :user").param("user", userId).update();
    }

    private static Instant ts(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** Резервные коды: 10 символов без похожих букв, вводятся с дефисом или без. */
    static final class RecoveryCodes {

        private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

        private RecoveryCodes() {
        }

        static String generate() {
            byte[] random = Secrets.randomBytes(10);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < random.length; i++) {
                if (i == 5) {
                    sb.append('-');
                }
                sb.append(ALPHABET.charAt((random[i] & 0xFF) % ALPHABET.length()));
            }
            return sb.toString();
        }

        static String normalize(String code) {
            return code.replaceAll("[\\s-]", "").toLowerCase(java.util.Locale.ROOT);
        }
    }
}
