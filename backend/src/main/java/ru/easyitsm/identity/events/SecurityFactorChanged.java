package ru.easyitsm.identity.events;

import java.util.UUID;

/**
 * Изменён фактор входа: пароль, TOTP, ключ WebAuthn, резервные коды, сброс MFA администратором.
 * Владельцу отправляется оповещение (T-01).
 *
 * @param change password_changed, password_reset, totp_enabled, totp_disabled, webauthn_added,
 *               webauthn_removed, recovery_codes_generated, mfa_reset
 */
public record SecurityFactorChanged(UUID tenantId, UUID userId, String change, UUID actorId) {
}
