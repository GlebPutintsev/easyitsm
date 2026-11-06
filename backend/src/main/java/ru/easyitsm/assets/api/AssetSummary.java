package ru.easyitsm.assets.api;

import java.util.UUID;

/** Краткие сведения об активе для других модулей: карточка сотрудника, КЕ или заявки. */
public record AssetSummary(UUID id, String tag, String name, String kind, AssetState state, UUID holderId,
                           UUID ciId) {
}
