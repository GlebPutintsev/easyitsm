package ru.easyitsm.changes.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Порт к CMDB: имена КЕ и услуги, на которые влияет изменение (CHG-06). Реализация — {@link CmdbConfigurationItems}
 * поверх {@code ru.easyitsm.cmdb.api}; сервис изменений от контракта CMDB напрямую не зависит, и тесты могут
 * подменить порт.
 */
interface ConfigurationItems {

    /** КЕ по id; отсутствующие пропускаются. */
    List<Ci> find(Collection<UUID> ids);

    /** Услуги, которые зависят от КЕ по связям CMDB, с путём от КЕ до услуги. */
    List<ImpactedService> impactedServices(Collection<UUID> ciIds, int depth);

    /** Есть ли настоящая CMDB: без неё интерфейс не предлагает «Показать влияние» по связям. */
    boolean available();

    record Ci(UUID id, String name, String className, boolean service) {
    }

    record ImpactedService(UUID serviceId, String name, int depth, List<UUID> path) {
    }
}
