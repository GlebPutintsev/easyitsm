package ru.easyitsm.identity.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Запрошен сброс пароля. Модуль уведомлений отправляет письмо со ссылкой {@code resetPath} на адрес {@code email}.
 * Ссылка одноразовая и действует до {@code expiresAt}.
 */
public record PasswordResetRequested(UUID tenantId, UUID userId, String email, String resetPath, Instant expiresAt) {
}
