/**
 * Вход и права доступа (раздел 3, 9.3, 10.3 ТЗ): пользователи, группы, роли (RBAC) и правила по атрибутам
 * записи (ABAC), локальный вход с Argon2id, LDAP, OIDC через Keycloak (SAML и Kerberos — через брокер Keycloak),
 * MFA TOTP и WebAuthn, сессии в куках с CSRF.
 * <p>
 * Другие модули пользуются только {@code api}: {@link ru.easyitsm.identity.api.UserDirectory} для сведений о людях
 * и группах, {@link ru.easyitsm.identity.api.AccessDecisions} для проверки доступа к записи,
 * {@link ru.easyitsm.identity.api.RequiresRecentAuth} для действий с повторной аутентификацией (SEC-08).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity and access")
package ru.easyitsm.identity;
