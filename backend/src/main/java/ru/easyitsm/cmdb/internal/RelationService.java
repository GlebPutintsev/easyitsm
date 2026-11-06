package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.events.CiImpactChanged;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/** Типизированные связи КЕ (CFG-03). Снятая связь не удаляется, а закрывается датой для истории (CFG-06). */
@Service
class RelationService {

    /** Короткая ссылка на КЕ на другом конце связи. */
    record CiLink(UUID id, String name, String classCode, String className, String layer, String status) {

        static CiLink of(CiRow r) {
            return new CiLink(r.id(), r.name(), r.classCode(), r.className(), r.layer().code(), r.status().code());
        }
    }

    /**
     * Связь с точки зрения КЕ: {@code outgoing} — КЕ источник, подпись {@code label} = название типа
     * («Работает на»), {@code incoming} — КЕ цель, подпись = обратное название («Выполняет»).
     */
    record RelationView(UUID id, String typeCode, String label, String direction, String impactDirection,
                        CiLink other, String origin, Instant validFrom) {
    }

    record Edge(UUID id, UUID typeId, UUID sourceId, UUID targetId, String origin, Instant validFrom) {
    }

    private final JdbcClient jdbc;
    private final CiService cis;
    private final CiStore store;
    private final CiClasses classes;
    private final ApplicationEventPublisher events;

    RelationService(JdbcClient jdbc, CiService cis, CiStore store, CiClasses classes,
                    ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.cis = cis;
        this.store = store;
        this.classes = classes;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<RelationView> list(UUID ciId, Instant at) {
        cis.require(ciId);
        Map<UUID, CiClasses.RelationType> types = typesById();
        List<Edge> edges = jdbc.sql("""
                        select id, type_id, source_id, target_id, origin, valid_from from ci_relation
                         where (source_id = :id or target_id = :id)
                           and (cast(:at as timestamptz) is null and valid_to is null
                             or valid_from <= cast(:at as timestamptz)
                                and (valid_to is null or valid_to > cast(:at as timestamptz)))
                         order by valid_from, id""")
                .param("id", ciId).param("at", Sql.ts(at))
                .query((rs, n) -> new Edge(Sql.uuid(rs, "id"), Sql.uuid(rs, "type_id"), Sql.uuid(rs, "source_id"),
                        Sql.uuid(rs, "target_id"), rs.getString("origin"), Sql.instant(rs, "valid_from")))
                .list();
        List<UUID> others = edges.stream().map(e -> e.sourceId().equals(ciId) ? e.targetId() : e.sourceId())
                .distinct().toList();
        Map<UUID, CiRow> rows = store.findAll(others).stream()
                .collect(Collectors.toMap(CiRow::id, Function.identity()));
        List<RelationView> out = new ArrayList<>();
        for (Edge e : edges) {
            boolean outgoing = e.sourceId().equals(ciId);
            CiRow other = rows.get(outgoing ? e.targetId() : e.sourceId());
            CiClasses.RelationType t = types.get(e.typeId());
            if (other == null || t == null) {
                continue;
            }
            out.add(new RelationView(e.id(), t.code(), outgoing ? t.name() : t.inverseName(),
                    outgoing ? "outgoing" : "incoming", t.impactDirection(), CiLink.of(other), e.origin(),
                    e.validFrom()));
        }
        out.sort((a, b) -> a.label().compareTo(b.label()) != 0 ? a.label().compareTo(b.label())
                : a.other().name().compareToIgnoreCase(b.other().name()));
        return out;
    }

    @Transactional
    public RelationView add(UUID ciId, CiCommands.AddRelation cmd) {
        CiRow self = store.lock(ciId).orElseThrow(() -> ApiException.notFound("КЕ"));
        if (cmd.targetId() == null) {
            throw CmdbProblem.invalid("field_required", "targetId", "Укажите КЕ на другом конце связи");
        }
        CiRow other = store.find(cmd.targetId())
                .orElseThrow(() -> CmdbProblem.invalid("unknown_ci", "targetId", "КЕ не найдена"));
        if (other.id().equals(self.id())) {
            throw CmdbProblem.invalid("self_relation", "targetId", "КЕ нельзя связать с самой собой");
        }
        if (self.status() == CiStatus.RETIRED || other.status() == CiStatus.RETIRED) {
            throw CmdbProblem.invalid("ci_retired", "targetId", "КЕ выведена из эксплуатации");
        }
        CiClasses.RelationType type = classes.relationTypes().stream()
                .filter(t -> t.code().equals(cmd.typeCode()) && t.active()).findFirst()
                .orElseThrow(() -> CmdbProblem.invalid("unknown_relation_type", "typeCode", "Тип связи не найден"));
        boolean outgoing = !"incoming".equals(cmd.direction());
        UUID source = outgoing ? self.id() : other.id();
        UUID target = outgoing ? other.id() : self.id();
        Integer exists = jdbc.sql("""
                        select count(*) from ci_relation
                         where valid_to is null and type_id = :type and source_id = :source and target_id = :target""")
                .param("type", type.id()).param("source", source).param("target", target)
                .query(Integer.class).single();
        if (exists > 0) {
            throw CmdbProblem.clash("relation_exists", "Такая связь уже есть");
        }
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        UUID id = insert(type.id(), source, target, CiService.MANUAL, actor, now);
        Map<String, Object> value = Map.of("type", type.code(), "source", source, "target", target);
        store.history(source, "relation", null, value, CiService.MANUAL, actor, now);
        store.history(target, "relation", null, value, CiService.MANUAL, actor, now);
        if (type.propagates()) {
            events.publishEvent(new CiImpactChanged(TenantContext.require(), now, actor, CiService.MANUAL,
                    Set.of(source, target), "relation_added"));
        }
        return new RelationView(id, type.code(), outgoing ? type.name() : type.inverseName(),
                outgoing ? "outgoing" : "incoming", type.impactDirection(), CiLink.of(other), CiService.MANUAL, now);
    }

    @Transactional
    public void remove(UUID relationId) {
        Edge e = jdbc.sql("""
                        select id, type_id, source_id, target_id, origin, valid_from from ci_relation
                         where id = :id and valid_to is null for update""")
                .param("id", relationId)
                .query((rs, n) -> new Edge(Sql.uuid(rs, "id"), Sql.uuid(rs, "type_id"), Sql.uuid(rs, "source_id"),
                        Sql.uuid(rs, "target_id"), rs.getString("origin"), Sql.instant(rs, "valid_from")))
                .optional().orElseThrow(() -> ApiException.notFound("Связь"));
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        jdbc.sql("""
                update ci_relation set valid_to = :now, updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", relationId).param("now", Sql.ts(now)).param("actor", actor).update();
        CiClasses.RelationType type = typesById().get(e.typeId());
        Map<String, Object> value = Map.of("type", type.code(), "source", e.sourceId(), "target", e.targetId());
        store.history(e.sourceId(), "relation", value, null, CiService.MANUAL, actor, now);
        store.history(e.targetId(), "relation", value, null, CiService.MANUAL, actor, now);
        if (type.propagates()) {
            events.publishEvent(new CiImpactChanged(TenantContext.require(), now, actor, CiService.MANUAL,
                    Set.of(e.sourceId(), e.targetId()), "relation_removed"));
        }
    }

    UUID insert(UUID typeId, UUID source, UUID target, String origin, UUID actor, Instant now) {
        return insert(typeId, source, target, origin, null, actor, now);
    }

    /** @param originCiId КЕ, в объекте источника которой объявлена связь; для ручных связей {@code null} */
    UUID insert(UUID typeId, UUID source, UUID target, String origin, UUID originCiId, UUID actor, Instant now) {
        UUID id = Ids.newId();
        jdbc.sql("""
                insert into ci_relation (id, tenant_id, type_id, source_id, target_id, origin, origin_ci_id,
                                         valid_from, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :type, :source, :target, :origin, :originCi, :now, :now, :actor, :now,
                        :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("type", typeId)
                .param("source", source).param("target", target).param("origin", origin)
                .param("originCi", originCiId)
                .param("now", Sql.ts(now)).param("actor", actor)
                .update();
        return id;
    }

    private Map<UUID, CiClasses.RelationType> typesById() {
        return classes.relationTypes().stream()
                .collect(Collectors.toMap(CiClasses.RelationType::id, Function.identity()));
    }
}
