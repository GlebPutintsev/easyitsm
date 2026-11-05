package ru.easyitsm.identity.internal;

import com.webauthn4j.WebAuthnManager;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class IdentityConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfig.class);

    @Bean
    Secrets identitySecrets(IdentityProperties props, Environment env) {
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        boolean devOrTest = profiles.contains("dev") || profiles.contains("test");
        if (!devOrTest && (!props.mfa().enforced() || !props.session().secureCookie())) {
            throw new IllegalStateException("easyitsm.identity.mfa.enforced=false и session.secure-cookie=false "
                    + "разрешены только в профилях dev и test (SEC-02, SEC-06)");
        }
        String key = props.secretKey();
        if (key == null || key.isBlank()) {
            if (!devOrTest) {
                throw new IllegalStateException("Не задан easyitsm.identity.secret-key (EASYITSM_IDENTITY_SECRET_KEY): "
                        + "32 случайных байта в base64, например `openssl rand -base64 32`");
            }
            log.warn("easyitsm.identity.secret-key не задан: используется временный ключ, TOTP сбросится после перезапуска");
            return new Secrets(Secrets.randomBytes(32));
        }
        return new Secrets(Base64.getDecoder().decode(key.trim()));
    }

    /**
     * Argon2id по рекомендации OWASP: 19 МиБ памяти, 2 прохода, 1 поток (SEC-04).
     * Параметры хранятся в самом хеше, поэтому их можно усилить без миграции: старые хеши обновятся при входе.
     */
    @Bean
    Argon2PasswordEncoder passwordHasher() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }

    @Bean
    WebAuthnManager webAuthnManager() {
        // Аттестация не проверяется: нужен только открытый ключ и подтверждение присутствия пользователя.
        return WebAuthnManager.createNonStrictWebAuthnManager();
    }
}
