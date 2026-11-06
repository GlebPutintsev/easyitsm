package ru.easyitsm.changes.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.cmdb.api.CiDirectory;
import ru.easyitsm.cmdb.api.CiImpact;
import ru.easyitsm.cmdb.api.CiRef;

/** {@link ConfigurationItems} поверх контракта CMDB: имена КЕ и услуги, зависящие от них по связям (CHG-06). */
@Component
class CmdbConfigurationItems implements ConfigurationItems {

    private final CiDirectory directory;
    private final CiImpact impact;

    CmdbConfigurationItems(CiDirectory directory, CiImpact impact) {
        this.directory = directory;
        this.impact = impact;
    }

    @Override
    public List<Ci> find(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return directory.findAll(ids).stream().map(CmdbConfigurationItems::ci).toList();
    }

    @Override
    public List<ImpactedService> impactedServices(Collection<UUID> ciIds, int depth) {
        if (ciIds == null || ciIds.isEmpty()) {
            return List.of();
        }
        return impact.impactedServices(ciIds, Math.clamp(depth, 1, CiImpact.MAX_DEPTH)).stream()
                .map(i -> new ImpactedService(i.ci().id(), i.ci().name(), i.depth(), i.path()))
                .toList();
    }

    @Override
    public boolean available() {
        return true;
    }

    private static Ci ci(CiRef r) {
        return new Ci(r.id(), r.name(), r.className(), r.isService());
    }
}
