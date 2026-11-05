package ru.easyitsm.knowledge.internal;

import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Категория базы знаний. */
@Table("kb_category")
class KbCategory extends TenantEntity {

    private String name;
    private String description;
    private int sortOrder;

    protected KbCategory() {
    }

    KbCategory(String name, String description, int sortOrder) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.sortOrder = sortOrder;
    }

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    KnowledgeDtos.CategoryView toView() {
        return new KnowledgeDtos.CategoryView(getId(), name, description, sortOrder, getVersion());
    }
}
