package ru.easyitsm.assets.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Конфигурационные единицы из модуля cmdb (AST-05). Пока cmdb не опубликовал api, реализация
 * {@link NoCmdbLookup} не знает ни одной КЕ: ссылка хранится, но имя не показывается.
 */
interface CiLookup {

    /** КЕ по id в арендаторе вызывающего; отсутствующие пропускаются. */
    Map<UUID, AssetDtos.CiInfo> find(Collection<UUID> ids);

    /** Есть ли КЕ (для проверки ссылки при сохранении). */
    boolean exists(UUID id);

    /** Подключён ли модуль cmdb: без него ссылка на КЕ принимается без проверки. */
    boolean available();
}
