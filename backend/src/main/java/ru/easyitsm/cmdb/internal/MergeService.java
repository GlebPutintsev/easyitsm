package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.events.CiImpactChanged;
import ru.easyitsm.cmdb.events.CiMerged;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Слияние дубликатов КЕ (CFG-05, CFG-07). Остаётся {@code keep}: он получает недостающие атрибуты и группу,
 * связи и объекты источников дубликата. Дубликат выводится из эксплуатации с {@code merged_into}, его история
 * и ссылки других модулей на него сохраняются; модули переводят ссылки по событию {@link CiMerged}.
 */
@Service
class MergeService {

    record MergeRequest(UUID mergedId) {
    }

    private record Edge(UUID id, UUID typeId, UUID sourceId, UUID targetId, String origin, UUID originCiId) {
    }

    private final JdbcClient jdbc;
    private final CiStore store;
    private final CiService cis;
    private final CiClasses classes;
    private final RelationService relations;
    private final ApplicationEventPublisher events;

    MergeService(JdbcClient jdbc, CiStore store, CiService cis, CiClasses classes, RelationService relations,
                 ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.cis = cis;
        this.classes = classes;
        this.relations = relations;
        this.events = events;
    }

    /** Ручное слияние с экрана «Качество данных»: {@code mergedId} сливается в {@code keepId}. */
    @Transactional
    public CiView merge(UUID keepId, UUID mergedId) {
        cis.require(keepId);
        if (mergedId == null) {
            throw CmdbProblem.invalid("field_required", "mergedId", "Укажите дубликат");
        }
        merge(keepId, mergedId, CiService.MANUAL, CurrentActor.userId().orElse(null), Sql.now(),
                events::publishEvent);
        return cis.get(keepId);
    }

    void merge(UUID keepId, UUID dupId, String source, UUID actor, Instant now, Consumer<Object> sink) {
        if (keepId.equals(dupId)) {
            throw CmdbProblem.invalid("self_merge", "mergedId", "КЕ нельзя слить с самой собой");
        }
        // Блокировки в одном порядке, чтобы встречные слияния не ждали друг друга вечно.
        boolean keepFirst = keepId.compareTo(dupId) < 0;
        CiRow first = store.lock(keepFirst ? keepId : dupId).orElse(null);
        CiRow second = store.lock(keepFirst ? dupId : keepId).orElse(null);
        CiRow keep = keepFirst ? first : second;
        CiRow dup = keepFirst ? second : first;
        if (keep == null) {
            throw ApiException.notFound("КЕ");
        }
        if (dup == null) {
            throw CmdbProblem.invalid("unknown_ci", "mergedId", "КЕ не найдена");
        }
        if (keep.mergedInto() != null || dup.mergedInto() != null) {
            throw CmdbProblem.clash("ci_merged", "КЕ уже слита с другой");
        }
        ClassTree tree = classes.tree();
        UUID keepRoot = tree.byId(keep.classId()).map(c -> tree.lineage(c).getFirst().id()).orElse(null);
        UUID dupRoot = tree.byId(dup.classId()).map(c -> tree.lineage(c).getFirst().id()).orElse(null);
        if (!Objects.equals(keepRoot, dupRoot)) {
            throw CmdbProblem.invalid("incompatible_class", "mergedId",
                    "Сливать можно КЕ одной ветки классов: «" + keep.className() + "» и «" + dup.className() + "»");
        }

        // Недостающее у остающейся КЕ берётся у дубликата вместе с источником значения.
        Map<String, Object> attrs = new LinkedHashMap<>(keep.attributes());
        Map<String, Object> sources = new LinkedHashMap<>(keep.attributeSources());
        for (Map.Entry<String, Object> e : dup.attributes().entrySet()) {
            if (!attrs.containsKey(e.getKey()) && allowed(tree, keep, e.getKey())) {
                attrs.put(e.getKey(), e.getValue());
                sources.put(e.getKey(), dup.attributeSources().getOrDefault(e.getKey(), CiService.MANUAL));
            }
        }
        Instant lastSeen = keep.lastSeenAt() == null ? dup.lastSeenAt()
                : dup.lastSeenAt() == null || keep.lastSeenAt().isAfter(dup.lastSeenAt()) ? keep.lastSeenAt()
                : dup.lastSeenAt();
        CiRow keepNext = new CiRow(keep.id(), keep.classId(), keep.classCode(), keep.className(), keep.layer(),
                keep.name(), keep.status(), keep.environment() == null ? dup.environment() : keep.environment(),
                keep.criticality(), keep.ownerGroupId() == null ? dup.ownerGroupId() : keep.ownerGroupId(),
                keep.description().isBlank() ? dup.description() : keep.description(), attrs, sources, lastSeen,
                keep.createdAt(), keep.createdBy(), now, actor, keep.version() + 1, null);
        CiRow dupNext = new CiRow(dup.id(), dup.classId(), dup.classCode(), dup.className(), dup.layer(),
                dup.name(), CiStatus.RETIRED, dup.environment(), dup.criticality(), dup.ownerGroupId(),
                dup.description(), dup.attributes(), dup.attributeSources(), dup.lastSeenAt(), dup.createdAt(),
                dup.createdBy(), now, actor, dup.version() + 1, keep.id());

        moveRelations(keep, dup, source, actor, now);
        jdbc.sql("update ci_source_record set ci_id = :keep where ci_id = :dup")
                .param("keep", keep.id()).param("dup", dup.id()).update();
        jdbc.sql("""
                update ci_conflict set status = 'dismissed', resolved_at = :now, resolved_by = :actor,
                                       updated_at = :now, version = version + 1
                 where ci_id = :dup and status = 'open'""")
                .param("dup", dup.id()).param("now", Sql.ts(now)).param("actor", actor).update();
        jdbc.sql("update ci set merged_into = :keep where merged_into = :dup")
                .param("keep", keep.id()).param("dup", dup.id()).update();

        Set<String> keepChanged = cis.recordChanges(keep, keepNext, source, actor, now);
        store.update(keepNext);
        Set<String> dupChanged = cis.recordChanges(dup, dupNext, source, actor, now);
        store.update(dupNext);
        jdbc.sql("update ci set merged_into = :keep where id = :dup")
                .param("keep", keep.id()).param("dup", dup.id()).update();
        store.history(keep.id(), "merged", null, Map.of("mergedId", dup.id(), "name", dup.name()), source, actor,
                now);
        store.history(dup.id(), "merged_into", null, Map.of("ciId", keep.id(), "name", keep.name()), source, actor,
                now);

        UUID tenant = TenantContext.require();
        cis.publishChanges(keep, keepNext, keepChanged, source, actor, now, sink);
        cis.publishChanges(dup, dupNext, dupChanged, source, actor, now, sink);
        sink.accept(new CiMerged(tenant, now, actor, source, keepNext.ref(), dup.id()));
        sink.accept(new CiImpactChanged(tenant, now, actor, source, Set.of(keep.id(), dup.id()), "merged"));
    }

    private boolean allowed(ClassTree tree, CiRow ci, String attribute) {
        return tree.byId(ci.classId())
                .map(c -> tree.effectiveAttributes(c).stream().anyMatch(a -> a.code().equals(attribute)))
                .orElse(false);
    }

    /** Связи дубликата закрываются и заводятся заново на остающейся КЕ, кроме петель и уже существующих. */
    private void moveRelations(CiRow keep, CiRow dup, String source, UUID actor, Instant now) {
        List<Edge> edges = jdbc.sql("""
                        select id, type_id, source_id, target_id, origin, origin_ci_id from ci_relation
                         where valid_to is null and (source_id = :dup or target_id = :dup)
                         order by valid_from, id for update""")
                .param("dup", dup.id())
                .query((rs, n) -> new Edge(Sql.uuid(rs, "id"), Sql.uuid(rs, "type_id"), Sql.uuid(rs, "source_id"),
                        Sql.uuid(rs, "target_id"), rs.getString("origin"), Sql.uuid(rs, "origin_ci_id")))
                .list();
        Map<UUID, CiClasses.RelationType> types = new LinkedHashMap<>();
        classes.relationTypes().forEach(t -> types.put(t.id(), t));
        for (Edge e : edges) {
            jdbc.sql("""
                    update ci_relation set valid_to = :now, updated_at = :now, updated_by = :actor,
                                           version = version + 1
                     where id = :id""")
                    .param("id", e.id()).param("now", Sql.ts(now)).param("actor", actor).update();
            UUID s = e.sourceId().equals(dup.id()) ? keep.id() : e.sourceId();
            UUID t = e.targetId().equals(dup.id()) ? keep.id() : e.targetId();
            if (s.equals(t)) {
                continue;
            }
            Integer exists = jdbc.sql("""
                            select count(*) from ci_relation
                             where valid_to is null and type_id = :type and source_id = :s and target_id = :t""")
                    .param("type", e.typeId()).param("s", s).param("t", t).query(Integer.class).single();
            if (exists > 0) {
                continue;
            }
            UUID originCi = dup.id().equals(e.originCiId()) ? keep.id() : e.originCiId();
            relations.insert(e.typeId(), s, t, e.origin(), originCi, actor, now);
            CiClasses.RelationType type = types.get(e.typeId());
            Map<String, Object> value = Map.of("type", type == null ? "" : type.code(), "source", s, "target", t);
            store.history(keep.id(), "relation", null, value, source, actor, now);
        }
        jdbc.sql("update ci_relation set origin_ci_id = :keep where origin_ci_id = :dup and valid_to is null")
                .param("keep", keep.id()).param("dup", dup.id()).update();
    }
}
