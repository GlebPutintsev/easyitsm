package ru.easyitsm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Вход для разработки по заголовкам {@code X-Dev-User} и {@code X-Dev-Tenant}.
 * Включается только в профилях dev и test, иначе приложение не стартует.
 * Будет заменён модулем identity.
 */
@ConfigurationProperties("easyitsm.security.dev-auth")
public record DevAuthProperties(boolean enabled) {
}
