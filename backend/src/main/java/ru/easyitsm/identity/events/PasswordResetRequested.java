package ru.easyitsm.identity.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Запрошен сброс пароля. Модуль уведомлений отправляет письмо со ссылкой {@code resetUrl} на адрес {@code email}.
 * Ссылка одноразовая и действует до {@code expiresAt}; в журналы и аудит её записывать нельзя.
 */
public record PasswordResetRequested(UUID tenantId, UUID userId, String email, String resetUrl, Instant expiresAt) {
}
