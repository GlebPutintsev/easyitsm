package ru.easyitsm.identity.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки входа и сессий. Значения по умолчанию соответствуют ТЗ; в «Управлении» их можно будет
 * переопределить для арендатора (тред «Управление и мастер настройки»).
 *
 * @param secretKey     ключ AES-256 в base64 для шифрования секретов TOTP и запроса OIDC; обязателен вне dev и test
 * @param publicUrl     адрес интерфейса для ссылок в письмах, например https://itsm.example.ru
 * @param defaultRoles  роли, которые есть у каждого активного пользователя
 */
@ConfigurationProperties("easyitsm.identity")
record IdentityProperties(
        String secretKey,
        @DefaultValue("http://localhost:5173") String publicUrl,
        @DefaultValue("REQ") Set<String> defaultRoles,
        @DefaultValue Session session,
        @DefaultValue Password password,
        @DefaultValue Lockout lockout,
        @DefaultValue Mfa mfa,
        @DefaultValue Webauthn webauthn,
        @DefaultValue Access access,
        @DefaultValue Ldap ldap,
        @DefaultValue Bootstrap bootstrap) {

    /**
     * SEC-06, SEC-07.
     *
     * @param idleTimeout           простой обычной сессии
     * @param privilegedIdleTimeout простой сессии с ролью ADM или SEC
     * @param absoluteTimeout       абсолютный срок сессии
     * @param pendingTimeout        срок на ввод второго фактора
     * @param reauthWindow          сколько действует повторная аутентификация (SEC-08)
     * @param secureCookie          Secure и префикс {@code __Host-}; выключается только для разработки по http
     * @param sameSite              Lax или Strict
     */
    record Session(
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("15m") Duration privilegedIdleTimeout,
            @DefaultValue("12h") Duration absoluteTimeout,
            @DefaultValue("10m") Duration pendingTimeout,
            @DefaultValue("5m") Duration reauthWindow,
            @DefaultValue("true") boolean secureCookie,
            @DefaultValue("Lax") String sameSite) {
    }

    /**
     * SEC-04.
     *
     * @param localLoginEnabled вход по локальным учётным записям (SEC-01: можно отключить)
     * @param breachedListFile  дополнительный файл утёкших паролей заказчика: по строке на пароль или SHA-1 в hex
     */
    record Password(
            @DefaultValue("true") boolean localLoginEnabled,
            @DefaultValue("12") int minLength,
            @DefaultValue("256") int maxLength,
            String breachedListFile,
            @DefaultValue("1h") Duration resetTokenTtl) {
    }

    /**
     * SEC-05: задержки, временная блокировка, оповещение.
     *
     * @param maxFailures        неудачных попыток до блокировки учётной записи
     * @param lockDuration       длительность блокировки
     * @param failureDelay       задержка ответа за каждую неудачную попытку подряд, не больше {@code maxDelay}
     * @param ipMaxFailures      неудачных попыток с одного адреса за {@code ipWindow}
     */
    record Lockout(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("300ms") Duration failureDelay,
            @DefaultValue("2s") Duration maxDelay,
            @DefaultValue("30") int ipMaxFailures,
            @DefaultValue("15m") Duration ipWindow) {
    }

    /**
     * SEC-02.
     *
     * @param enforced              требовать MFA по ролям (SEC-02); выключается только для разработки
     * @param requiredForAll        требовать MFA и от REQ, APR (политика заказчика)
     * @param rememberDeviceDays    «Запомнить устройство», 0 — запрещено
     * @param issuer                название в приложении-аутентификаторе
     * @param trustSsoMfa           считать вход через SSO прошедшим MFA (MFA проверяет IdP, например Keycloak)
     */
    record Mfa(
            @DefaultValue("true") boolean enforced,
            @DefaultValue("false") boolean requiredForAll,
            @DefaultValue("30") int rememberDeviceDays,
            @DefaultValue("Easy ITSM") String issuer,
            @DefaultValue("true") boolean trustSsoMfa) {
    }

    /**
     * @param rpId    домен приложения без схемы и порта
     * @param origins адреса, с которых открывается интерфейс
     */
    record Webauthn(
            @DefaultValue("localhost") String rpId,
            @DefaultValue("Easy ITSM") String rpName,
            @DefaultValue({"http://localhost:5173", "http://localhost:8080"}) List<String> origins) {
    }

    /**
     * ABAC-политика по умолчанию.
     *
     * @param requesterSeesOrganization заявитель видит заявки своей организации, а не только свои
     */
    record Access(@DefaultValue("false") boolean requesterSeesOrganization) {
    }

    /**
     * LDAP: AD, FreeIPA, ALD Pro, OpenLDAP. Вход по паролю каталога и синхронизация (ACC-10).
     *
     * @param tenantId          арендатор, в который синхронизируется каталог (по умолчанию арендатор инсталляции)
     * @param userFilter        фильтр пользователей; {0} при входе подставляется логин
     * @param disabledFilter    какие из найденных пользователей отключены в каталоге
     * @param externalIdAttribute атрибут неизменяемого идентификатора: entryUUID, objectGUID, ipaUniqueID
     * @param syncInterval      период синхронизации; должен быть не больше 15 минут (ACC-10)
     */
    record Ldap(
            @DefaultValue("false") boolean enabled,
            UUID tenantId,
            @DefaultValue("ldap://localhost:389") String url,
            @DefaultValue("") String base,
            String bindDn,
            String bindPassword,
            @DefaultValue("(&(objectClass=person)(uid={0}))") String userFilter,
            @DefaultValue("") String disabledFilter,
            @DefaultValue("(objectClass=groupOfNames)") String groupFilter,
            @DefaultValue("uid") String loginAttribute,
            @DefaultValue("mail") String emailAttribute,
            @DefaultValue("cn") String displayNameAttribute,
            @DefaultValue("entryUUID") String externalIdAttribute,
            @DefaultValue("cn") String groupNameAttribute,
            @DefaultValue("member") String memberAttribute,
            @DefaultValue("10m") Duration syncInterval) {
    }

    /**
     * Первый администратор новой инсталляции. Создаётся при старте, если в арендаторе ещё нет пользователей.
     * Пароль передаётся через переменную окружения и после первого входа меняется.
     */
    record Bootstrap(String adminLogin, String adminPassword, String adminEmail) {
    }
}
