package ru.easyitsm.audit.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Запись для {@link AuditLog#record}. Кто, откуда и трассировка берутся из текущего запроса, если не заданы
 * явно: так модулю достаточно назвать действие, объект и результат (SEC-30).
 * <p>
 * Код действия — латиница через точку, от раздела к действию: {@code data.export}, {@code tickets.secret.reveal}.
 * Детали — небольшой плоский набор значений; ключи вроде {@code password} и {@code token} и найденные в тексте
 * секреты маскируются ({@link Masking}), но передавать их сюда всё равно нельзя.
 */
public final class AuditEntry {

    private final AuditCategory category;
    private final String action;
    private AuditResult result = AuditResult.SUCCESS;
    private String objectType;
    private String objectId;
    private UUID actorId;
    private String actorLogin;
    private String ip;
    private final Map<String, Object> details = new LinkedHashMap<>();

    private AuditEntry(AuditCategory category, String action) {
        this.category = Objects.requireNonNull(category);
        this.action = Objects.requireNonNull(action);
    }

    public static AuditEntry of(AuditCategory category, String action) {
        return new AuditEntry(category, action);
    }

    public AuditEntry result(AuditResult result) {
        this.result = Objects.requireNonNull(result);
        return this;
    }

    public AuditEntry object(String type, Object id) {
        this.objectType = type;
        this.objectId = id == null ? null : String.valueOf(id);
        return this;
    }

    /** Исполнитель, если действие выполнено не текущим пользователем запроса (фоновая задача, событие). */
    public AuditEntry actor(UUID userId, String login) {
        this.actorId = userId;
        this.actorLogin = login;
        return this;
    }

    /** Адрес клиента, если запись делается не в потоке его запроса. */
    public AuditEntry ip(String ip) {
        this.ip = ip;
        return this;
    }

    public AuditEntry detail(String key, Object value) {
        if (value != null) {
            details.put(key, value);
        }
        return this;
    }

    public AuditCategory category() {
        return category;
    }

    public String action() {
        return action;
    }

    public AuditResult result() {
        return result;
    }

    public String objectType() {
        return objectType;
    }

    public String objectId() {
        return objectId;
    }

    public UUID actorId() {
        return actorId;
    }

    public String actorLogin() {
        return actorLogin;
    }

    public String ip() {
        return ip;
    }

    public Map<String, Object> details() {
        return details;
    }
}
