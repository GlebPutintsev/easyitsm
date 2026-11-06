package ru.easyitsm.assets.internal;

import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Поставщик оборудования, ПО и услуг (AST-04). */
@Table("asset_vendor")
class Vendor extends TenantEntity {

    private String name;
    private String taxId;
    private String contactName;
    private String email;
    private String phone;
    private String website;
    private String notes;
    private boolean active;

    protected Vendor() {
    }

    Vendor(String name) {
        this.name = name;
        this.notes = "";
        this.active = true;
    }

    void apply(AssetDtos.VendorFields f) {
        if (f.name() != null) {
            name = f.name().strip();
        }
        if (f.taxId() != null) {
            taxId = AssetDtos.blankToNull(f.taxId());
        }
        if (f.contactName() != null) {
            contactName = AssetDtos.blankToNull(f.contactName());
        }
        if (f.email() != null) {
            email = AssetDtos.blankToNull(f.email());
        }
        if (f.phone() != null) {
            phone = AssetDtos.blankToNull(f.phone());
        }
        if (f.website() != null) {
            website = AssetDtos.blankToNull(f.website());
        }
        if (f.notes() != null) {
            notes = f.notes();
        }
        if (f.active() != null) {
            active = f.active();
        }
    }

    String name() {
        return name;
    }

    AssetDtos.VendorView toView() {
        return new AssetDtos.VendorView(getId(), name, taxId, contactName, email, phone, website, notes, active,
                getVersion());
    }
}
