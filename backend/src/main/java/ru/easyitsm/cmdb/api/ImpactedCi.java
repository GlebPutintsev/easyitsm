package ru.easyitsm.cmdb.api;

import java.util.List;
import java.util.UUID;

/**
 * КЕ, затронутая отказом.
 *
 * @param depth расстояние в связях от ближайшей исходной КЕ, 1…5
 * @param path  id КЕ от исходной до этой включительно (длина {@code depth + 1})
 */
public record ImpactedCi(CiRef ci, int depth, List<UUID> path) {

    public ImpactedCi {
        path = List.copyOf(path);
    }

    /** Исходная КЕ, от которой пришло влияние. */
    public UUID rootId() {
        return path.getFirst();
    }
}
