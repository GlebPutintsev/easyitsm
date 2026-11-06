package ru.easyitsm.cmdb.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiImpact;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.api.ImpactReport;
import ru.easyitsm.cmdb.api.ImpactedCi;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Анализ влияния (CFG-09): обход в ширину по активным связям в направлении влияния, по одному запросу на уровень.
 * Каждая КЕ попадает в отчёт один раз с кратчайшим путём; выведенные из эксплуатации КЕ влияние не передают.
 */
@Service
class ImpactService implements CiImpact {

    static final int MAX_CIS = 2000;
    static final int MAX_TICKETS = 100;

    private final JdbcClient jdbc;
    private final CiStore store;
    private final CmdbDefaults defaults;
    private final TicketQueries tickets;

    ImpactService(JdbcClient jdbc, CiStore store, CmdbDefaults defaults, TicketQueries tickets) {
        this.jdbc = jdbc;
        this.store = store;
        this.defaults = defaults;
        this.tickets = tickets;
    }

    @Override
    @Transactional(readOnly = true)
    public ImpactReport analyze(Collection<UUID> ciIds, int depth) {
        defaults.ensure();
        int maxDepth = Math.clamp(depth, 1, MAX_DEPTH);
        List<CiRow> roots = store.findAll(List.copyOf(new LinkedHashSet<>(ciIds)));
        Map<UUID, List<UUID>> paths = new LinkedHashMap<>();
        roots.forEach(r -> paths.put(r.id(), List.of(r.id())));
        Map<UUID, CiRow> rows = new HashMap<>();
        roots.forEach(r -> rows.put(r.id(), r));

        List<UUID> frontier = roots.stream().map(CiRow::id).toList();
        boolean truncated = false;
        int level = 0;
        while (!frontier.isEmpty() && level < maxDepth && !truncated) {
            level++;
            Map<UUID, UUID> next = new LinkedHashMap<>(); // найденная КЕ → откуда пришли
            for (UUID[] edge : impactEdges(frontier)) {
                UUID from = edge[0];
                UUID to = edge[1];
                if (!paths.containsKey(to) && !next.containsKey(to)) {
                    next.put(to, from);
                }
            }
            List<UUID> found = new ArrayList<>();
            for (CiRow r : store.findAll(List.copyOf(next.keySet()))) {
                if (r.status() == CiStatus.RETIRED) {
                    continue;
                }
                if (paths.size() - roots.size() >= MAX_CIS) {
                    truncated = true;
                    break;
                }
                List<UUID> path = new ArrayList<>(paths.get(next.get(r.id())));
                path.add(r.id());
                paths.put(r.id(), List.copyOf(path));
                rows.put(r.id(), r);
                found.add(r.id());
            }
            frontier = found;
        }

        List<ImpactedCi> impacted = new ArrayList<>();
        List<ImpactedCi> services = new ArrayList<>();
        Set<UUID> rootIds = new LinkedHashSet<>();
        roots.forEach(r -> rootIds.add(r.id()));
        for (Map.Entry<UUID, List<UUID>> e : paths.entrySet()) {
            CiRow r = rows.get(e.getKey());
            ImpactedCi item = new ImpactedCi(r.ref(), e.getValue().size() - 1, e.getValue());
            if (!rootIds.contains(r.id())) {
                impacted.add(item);
            }
            if (r.layer().isService()) {
                services.add(item);
            }
        }
        List<UUID> openTickets = paths.isEmpty() ? List.of()
                : tickets.openForObjects(paths.keySet(), MAX_TICKETS).stream().map(TicketSnapshot::id).toList();
        return new ImpactReport(roots.stream().map(CiRow::ref).toList(), impacted, services, openTickets, maxDepth,
                truncated);
    }

    /** Рёбра влияния из КЕ фронта: пары (откуда, куда). */
    private List<UUID[]> impactEdges(List<UUID> frontier) {
        return jdbc.sql("""
                        select case when t.impact_direction = 'target_to_source' then r.target_id else r.source_id end
                                   as from_id,
                               case when t.impact_direction = 'target_to_source' then r.source_id else r.target_id end
                                   as to_id
                          from ci_relation r join ci_relation_type t on t.id = r.type_id
                         where r.valid_to is null
                           and ((t.impact_direction = 'target_to_source' and r.target_id = any(:ids))
                             or (t.impact_direction = 'source_to_target' and r.source_id = any(:ids)))
                         order by r.valid_from, r.id""")
                .param("ids", frontier.toArray(UUID[]::new))
                .query((rs, n) -> new UUID[] {Sql.uuid(rs, "from_id"), Sql.uuid(rs, "to_id")})
                .list();
    }
}
