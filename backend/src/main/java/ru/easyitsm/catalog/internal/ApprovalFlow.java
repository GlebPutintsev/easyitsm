package ru.easyitsm.catalog.internal;

import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("approval_flow")
class ApprovalFlow extends TenantEntity {

    private String name;
    private String description;
    private String steps;
    private boolean active;

    protected ApprovalFlow() {
    }

    ApprovalFlow(String name) {
        this.name = name;
        this.description = "";
        this.steps = "[]";
        this.active = true;
    }

    void update(String name, String description, String stepsJson, boolean active) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.steps = stepsJson;
        this.active = active;
    }

    String steps() {
        return steps;
    }

    boolean active() {
        return active;
    }

    CatalogViews.FlowView toView(JsonCodec json) {
        return new CatalogViews.FlowView(getId(), name, description, json.steps(steps), active, getUpdatedAt(),
                getVersion());
    }
}
