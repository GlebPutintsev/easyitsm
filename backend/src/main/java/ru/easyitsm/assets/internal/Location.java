package ru.easyitsm.assets.internal;

import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Местоположение актива: офис, этаж, склад. */
@Table("asset_location")
class Location extends TenantEntity {

    private String name;
    private String address;
    private boolean active;

    protected Location() {
    }

    Location(String name, String address) {
        this.name = name;
        this.address = address;
        this.active = true;
    }

    void update(String name, String address, Boolean active) {
        if (name != null) {
            this.name = name;
        }
        if (address != null) {
            this.address = address.isBlank() ? null : address;
        }
        if (active != null) {
            this.active = active;
        }
    }

    AssetDtos.LocationView toView() {
        return new AssetDtos.LocationView(getId(), name, address, active, getVersion());
    }
}
