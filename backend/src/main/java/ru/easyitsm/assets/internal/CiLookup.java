package ru.easyitsm.assets.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.cmdb.api.CiDirectory;
import ru.easyitsm.cmdb.api.CiRef;

/**
 * Конфигурационные единицы из модуля cmdb (AST-05) через {@link CiDirectory}: проверка ссылки при сохранении
 * и имя, класс, статус КЕ в карточке актива. Модуль cmdb об активах не знает.
 */
@Component
class CiLookup {

    private static final int BATCH = 1000;

    private final CiDirectory directory;

    CiLookup(CiDirectory directory) {
        this.directory = directory;
    }

    /** КЕ по id в арендаторе вызывающего; отсутствующие пропускаются. */
    Map<UUID, AssetDtos.CiInfo> find(Collection<UUID> ids) {
        List<UUID> all = ids.stream().distinct().toList();
        Map<UUID, AssetDtos.CiInfo> found = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i += BATCH) {
            for (CiRef ci : directory.findAll(all.subList(i, Math.min(all.size(), i + BATCH)))) {
                found.put(ci.id(), info(ci));
            }
        }
        return found;
    }

    /** Есть ли КЕ в арендаторе (для проверки ссылки при сохранении). */
    boolean exists(UUID id) {
        return directory.find(id).isPresent();
    }

    static AssetDtos.CiInfo info(CiRef ci) {
        return new AssetDtos.CiInfo(ci.id(), ci.name(), ci.classCode(), ci.className(), ci.status().code());
    }
}
