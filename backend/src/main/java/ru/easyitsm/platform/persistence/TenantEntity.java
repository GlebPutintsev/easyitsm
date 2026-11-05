package ru.easyitsm.platform.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import ru.easyitsm.platform.id.Ids;

/**
 * Базовая сущность любой таблицы с данными арендатора (раздел 7.1 ТЗ):
 * UUIDv7, {@code tenant_id}, служебные поля аудита и {@code version} для оптимистичной блокировки.
 * <p>
 * {@code tenant_id} заполняется из {@link ru.easyitsm.platform.tenant.TenantContext} при первом сохранении,
 * служебные поля — через Spring Data auditing. Сущность новая, пока {@code version == null}.
 */
public abstract class TenantEntity {

    @Id
    private UUID id = Ids.newId();
    private UUID tenantId;
    @CreatedDate
    private Instant createdAt;
    @CreatedBy
    private UUID createdBy;
    @LastModifiedDate
    private Instant updatedAt;
    @LastModifiedBy
    private UUID updatedBy;
    @Version
    private Long version;

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Long getVersion() {
        return version;
    }
}
