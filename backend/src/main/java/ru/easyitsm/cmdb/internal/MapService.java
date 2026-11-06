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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiImpact;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Карта связей КЕ (CFG-03, экран 10.13): соседи в обе стороны до глубины 5, фильтр типов связей,
 * число открытых инцидентов и изменений на каждой КЕ для подсветки.
 */
@Service
class MapService {

    static final int MAX_NODES = 300;

    record Node(CmdbController.CiRefView ci, int depth, int openIncidents, int openChanges) {
    }

    record MapEdge(UUID id, UUID sourceId, UUID targetId, String typeCode, String name, String impactDirection) {
    }

    record CiMap(UUID rootId, List<Node> nodes, List<MapEdge> edges, int depth, boolean truncated) {
    }

    private record Rel(UUID id, UUID typeId, UUID sourceId, UUID targetId) {
    }

    private final JdbcClient jdbc;
    private final CiService cis;
    private final CiStore store;
    private final CiClasses classes;
    private final TicketQueries tickets;

    MapService(JdbcClient jdbc, CiService cis, CiStore store, CiClasses classes, TicketQueries tickets) {
        this.jdbc = jdbc;
        this.cis = cis;
        this.store = store;
        this.classes = classes;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public CiMap map(UUID rootId, int depth, Collection<String> typeCodes) {
        cis.require(rootId);
        int maxDepth = Math.clamp(depth, 1, CiImpact.MAX_DEPTH);
        Map<UUID, CiClasses.RelationType> types = classes.relationTypes().stream()
                .filter(t -> typeCodes.isEmpty() || typeCodes.contains(t.code()))
                .collect(Collectors.toMap(CiClasses.RelationType::id, Function.identity()));
        Map<UUID, Integer> depthOf = new LinkedHashMap<>();
        depthOf.put(rootId, 0);
        Map<UUID, Rel> edges = new LinkedHashMap<>();
        List<UUID> frontier = List.of(rootId);
        boolean truncated = false;
        for (int level = 1; level <= maxDepth && !frontier.isEmpty() && !truncated; level++) {
            List<UUID> next = new ArrayList<>();
            for (Rel r : neighbours(frontier, types.keySet())) {
                for (UUID other : List.of(r.sourceId(), r.targetId())) {
                    if (!depthOf.containsKey(other)) {
                        if (depthOf.size() >= MAX_NODES) {
                            truncated = true;
                            continue;
                        }
                        depthOf.put(other, level);
                        next.add(other);
                    }
                }
                if (depthOf.containsKey(r.sourceId()) && depthOf.containsKey(r.targetId())) {
                    edges.put(r.id(), r);
                }
            }
            frontier = next;
        }
        // Связи между уже найденными КЕ последнего уровня, чтобы карта не теряла рёбра.
        for (Rel r : neighbours(frontier, types.keySet())) {
            if (depthOf.containsKey(r.sourceId()) && depthOf.containsKey(r.targetId())) {
                edges.put(r.id(), r);
            }
        }

        Map<UUID, int[]> counts = new HashMap<>();
        for (TicketSnapshot t : tickets.openForObjects(depthOf.keySet(), 500)) {
            Set<UUID> objects = new LinkedHashSet<>();
            if (t.ciId() != null) {
                objects.add(t.ciId());
            }
            if (t.serviceId() != null) {
                objects.add(t.serviceId());
            }
            for (UUID o : objects) {
                int[] c = counts.computeIfAbsent(o, k -> new int[2]);
                if (t.type() == TicketType.INCIDENT) {
                    c[0]++;
                } else if (t.type() == TicketType.CHANGE) {
                    c[1]++;
                }
            }
        }
        List<Node> nodes = new ArrayList<>();
        for (CiRow r : store.findAll(List.copyOf(depthOf.keySet()))) {
            int[] c = counts.getOrDefault(r.id(), new int[2]);
            nodes.add(new Node(CmdbController.CiRefView.of(r.ref()), depthOf.get(r.id()), c[0], c[1]));
        }
        List<MapEdge> out = edges.values().stream().map(r -> {
            CiClasses.RelationType t = types.get(r.typeId());
            return new MapEdge(r.id(), r.sourceId(), r.targetId(), t.code(), t.name(), t.impactDirection());
        }).toList();
        return new CiMap(rootId, nodes, out, maxDepth, truncated);
    }

    private List<Rel> neighbours(List<UUID> ids, Set<UUID> typeIds) {
        if (ids.isEmpty() || typeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id, type_id, source_id, target_id from ci_relation
                         where valid_to is null and type_id = any(:types)
                           and (source_id = any(:ids) or target_id = any(:ids))
                         order by valid_from, id""")
                .param("ids", ids.toArray(UUID[]::new)).param("types", typeIds.toArray(UUID[]::new))
                .query((rs, n) -> new Rel(Sql.uuid(rs, "id"), Sql.uuid(rs, "type_id"), Sql.uuid(rs, "source_id"),
                        Sql.uuid(rs, "target_id")))
                .list();
    }
}
