package ru.easyitsm.catalog.internal;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("catalog_category")
class Category extends TenantEntity {

    private UUID parentId;
    private String name;
    private String description;
    private String icon;
    private int sortOrder;
    private boolean active;

    protected Category() {
    }

    Category(UUID parentId, String name) {
        this.parentId = parentId;
        this.name = name;
        this.description = "";
        this.active = true;
    }

    void update(UUID parentId, String name, String description, String icon, int sortOrder, boolean active) {
        this.parentId = parentId;
        this.name = name;
        this.description = description == null ? "" : description;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    UUID parentId() {
        return parentId;
    }

    boolean active() {
        return active;
    }

    CatalogViews.CategoryView toView() {
        return new CatalogViews.CategoryView(getId(), parentId, name, description, icon, sortOrder, active,
                getVersion());
    }
}
