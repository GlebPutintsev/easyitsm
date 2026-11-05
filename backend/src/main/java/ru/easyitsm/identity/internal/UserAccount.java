package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Профиль пользователя. Пароль, счётчики входа и факторы MFA — в {@link Credentials}. */
@Table("user_account")
class UserAccount extends TenantEntity {

    private String login;
    private String email;
    private String displayName;
    private String locale = "ru";
    private String timezone = "Europe/Moscow";
    private String state = "active";
    private String blockedReason;
    private String source;
    private String externalId;
    private UUID organizationId;
    private UUID departmentId;
    private UUID managerId;
    /** Меняется только запросом при входе, сохранение профиля его не перетирает. */
    @ReadOnlyProperty
    private Instant lastLoginAt;

    protected UserAccount() {
    }

    UserAccount(String login, String email, String displayName, String source, String externalId) {
        this.login = login;
        this.email = blankToNull(email);
        this.displayName = displayName;
        this.source = source;
        this.externalId = externalId;
    }

    String login() {
        return login;
    }

    String email() {
        return email;
    }

    String displayName() {
        return displayName;
    }

    String locale() {
        return locale;
    }

    String state() {
        return state;
    }

    String blockedReason() {
        return blockedReason;
    }

    String source() {
        return source;
    }

    String externalId() {
        return externalId;
    }

    boolean active() {
        return "active".equals(state);
    }

    boolean local() {
        return "local".equals(source);
    }

    void setLogin(String login) {
        this.login = login;
    }

    void setEmail(String email) {
        this.email = blankToNull(email);
    }

    void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    void setLocale(String locale) {
        this.locale = locale;
    }

    void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    void setManagerId(UUID managerId) {
        this.managerId = managerId;
    }

    void block(String reason) {
        this.state = "blocked";
        this.blockedReason = reason;
    }

    void unblock() {
        this.state = "active";
        this.blockedReason = null;
    }

    UserSummary toSummary() {
        return new UserSummary(getId(), login, displayName, email, locale, timezone, active(), organizationId,
                departmentId, managerId);
    }

    UserView toView() {
        return new UserView(getId(), login, email, displayName, locale, timezone, state, blockedReason, source,
                organizationId, departmentId, managerId, lastLoginAt, getCreatedAt(), getUpdatedAt(), getVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
