package ru.easyitsm.admin.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.admin.api.OrgUnitSummary;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("org_unit")
class OrgUnit extends TenantEntity {

    private String kind;
    private String name;
    private String code;
    private UUID parentId;
    private boolean active = true;

    protected OrgUnit() {
    }

    OrgUnit(String kind, String name, String code, UUID parentId) {
        this.kind = kind;
        this.name = name;
        this.code = code;
        this.parentId = parentId;
    }

    String kind() {
        return kind;
    }

    String name() {
        return name;
    }

    UUID parentId() {
        return parentId;
    }

    void setName(String name) {
        this.name = name;
    }

    void setCode(String code) {
        this.code = code;
    }

    void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    OrgUnitSummary toSummary() {
        return new OrgUnitSummary(getId(), kind, name, code, parentId, active);
    }

    View toView() {
        return new View(getId(), kind, name, code, parentId, active, getUpdatedAt(), getVersion());
    }

    record View(UUID id, String kind, String name, String code, UUID parentId, boolean active, Instant updatedAt,
                long version) {
    }
}
