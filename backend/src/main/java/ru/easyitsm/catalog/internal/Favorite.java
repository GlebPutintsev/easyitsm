package ru.easyitsm.catalog.internal;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("catalog_favorite")
class Favorite extends TenantEntity {

    private UUID userId;
    private UUID itemId;

    protected Favorite() {
    }

    Favorite(UUID userId, UUID itemId) {
        this.userId = userId;
        this.itemId = itemId;
    }
}
