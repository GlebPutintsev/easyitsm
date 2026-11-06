package ru.easyitsm.reporting.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.catalog.api.CatalogItemRef;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.identity.api.UserDirectory;

/** Имена групп и услуг для подписей строк: через API модулей identity и catalog, одним запросом на отчёт. */
@Component
class NameBook {

    private final UserDirectory users;
    private final CatalogQueries catalog;

    NameBook(UserDirectory users, CatalogQueries catalog) {
        this.users = users;
        this.catalog = catalog;
    }

    Names load(String lang) {
        Map<UUID, String> groups = new HashMap<>();
        for (GroupSummary g : users.listGroups(null)) {
            groups.put(g.id(), g.name());
        }
        Map<UUID, String> services = new HashMap<>();
        for (CatalogItemRef i : catalog.activeItems()) {
            services.put(i.id(), i.name());
        }
        return new Names(lang, groups, services);
    }

    record Names(String lang, Map<UUID, String> groups, Map<UUID, String> services) {

        String group(UUID id) {
            if (id == null) {
                return Labels.get(lang, "group.none");
            }
            return groups.getOrDefault(id, Labels.get(lang, "group.unknown"));
        }

        String service(UUID id) {
            if (id == null) {
                return Labels.get(lang, "service.none");
            }
            return services.getOrDefault(id, Labels.get(lang, "service.unknown"));
        }
    }
}
