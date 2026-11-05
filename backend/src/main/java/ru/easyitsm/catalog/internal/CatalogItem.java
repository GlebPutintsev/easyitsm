package ru.easyitsm.catalog.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("catalog_item")
class CatalogItem extends TenantEntity {

    private UUID categoryId;
    private String kind;
    private String name;
    private String summary;
    private String description;
    private String icon;
    private String team;
    private String keywords;
    private String form;
    private UUID approvalFlowId;
    private List<String> visibleRoles;
    private boolean allowOnBehalf;
    private BigDecimal cost;
    private String currency;
    private Integer leadTimeHours;
    private long orderCount;
    private int sortOrder;
    private boolean active;

    protected CatalogItem() {
    }

    CatalogItem(UUID categoryId, String name) {
        this.categoryId = categoryId;
        this.name = name;
        this.kind = "request";
        this.summary = "";
        this.description = "";
        this.keywords = "";
        this.form = "{\"fields\":[]}";
        this.visibleRoles = List.of();
        this.allowOnBehalf = true;
        this.active = true;
    }

    void update(CatalogCommands.ItemFields f, String formJson) {
        this.categoryId = f.categoryId();
        this.kind = f.kind() == null ? "request" : f.kind();
        this.name = f.name();
        this.summary = f.summary() == null ? "" : f.summary();
        this.description = f.description() == null ? "" : f.description();
        this.icon = f.icon();
        this.team = blankToNull(f.team());
        this.keywords = f.keywords() == null ? "" : f.keywords();
        this.form = formJson;
        this.approvalFlowId = f.approvalFlowId();
        this.visibleRoles = f.visibleRoles() == null ? List.of() : List.copyOf(f.visibleRoles());
        this.allowOnBehalf = f.allowOnBehalf() == null || f.allowOnBehalf();
        this.cost = f.cost();
        this.currency = f.cost() == null ? null : (f.currency() == null ? "RUB" : f.currency());
        this.leadTimeHours = f.leadTimeHours();
        this.sortOrder = f.sortOrder() == null ? 0 : f.sortOrder();
        this.active = f.active() == null || f.active();
    }

    /** Видимость по ролям (REQ-01): пустой список — видно всем. Администратор видит всё. */
    boolean visibleTo(Collection<String> roles, boolean admin) {
        if (admin) {
            return true;
        }
        return active && (visibleRoles.isEmpty() || visibleRoles.stream().anyMatch(roles::contains));
    }

    boolean active() {
        return active;
    }

    String kind() {
        return kind;
    }

    String name() {
        return name;
    }

    String form() {
        return form;
    }

    UUID categoryId() {
        return categoryId;
    }

    UUID approvalFlowId() {
        return approvalFlowId;
    }

    boolean allowOnBehalf() {
        return allowOnBehalf;
    }

    Integer leadTimeHours() {
        return leadTimeHours;
    }

    CatalogViews.ItemCard toCard(boolean favorite) {
        return new CatalogViews.ItemCard(getId(), categoryId, kind, name, summary, icon, team, cost, currency,
                leadTimeHours, favorite, active);
    }

    CatalogViews.ItemDetail toDetail(JsonCodec json, boolean favorite) {
        return new CatalogViews.ItemDetail(getId(), categoryId, kind, name, summary, description, icon, team, keywords,
                json.form(form), approvalFlowId, visibleRoles, allowOnBehalf, cost, currency, leadTimeHours, sortOrder,
                active, favorite, orderCount, getUpdatedAt(), getVersion());
    }

    ru.easyitsm.catalog.api.CatalogItemRef toRef() {
        return new ru.easyitsm.catalog.api.CatalogItemRef(getId(), name, summary, categoryId, kind);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
