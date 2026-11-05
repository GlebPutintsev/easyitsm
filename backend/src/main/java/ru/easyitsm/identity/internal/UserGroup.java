package ru.easyitsm.identity.internal;

import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("user_group")
class UserGroup extends TenantEntity {

    private String name;
    private String description = "";
    private String type = "support";
    private String source = "local";
    private String externalId;

    protected UserGroup() {
    }

    UserGroup(String name, String description, String type, String source, String externalId) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.type = type == null ? "support" : type;
        this.source = source;
        this.externalId = externalId;
    }

    String name() {
        return name;
    }

    String source() {
        return source;
    }

    String externalId() {
        return externalId;
    }

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setType(String type) {
        this.type = type;
    }

    GroupSummary toSummary() {
        return new GroupSummary(getId(), name, type);
    }

    GroupView toView() {
        return new GroupView(getId(), name, description, type, source, getUpdatedAt(), getVersion());
    }

    record GroupView(java.util.UUID id, String name, String description, String type, String source,
                     java.time.Instant updatedAt, long version) {
    }
}
