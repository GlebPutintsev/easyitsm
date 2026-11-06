package ru.easyitsm.assets.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Заглушка до появления {@code ru.easyitsm.cmdb.api}: КЕ неизвестны, ссылки не проверяются. */
@Component
class NoCmdbLookup implements CiLookup {

    @Override
    public Map<UUID, AssetDtos.CiInfo> find(Collection<UUID> ids) {
        return Map.of();
    }

    @Override
    public boolean exists(UUID id) {
        return false;
    }

    @Override
    public boolean available() {
        return false;
    }
}
