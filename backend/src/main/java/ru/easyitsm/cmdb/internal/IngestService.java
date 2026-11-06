package ru.easyitsm.cmdb.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.cmdb.api.CiCriticality;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.events.CiCreated;
import ru.easyitsm.cmdb.events.CiImpactChanged;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.core.type.TypeReference;

/**
 * Загрузка КЕ из источников пакетами и сверка (CFG-04, CFG-05). Один API для агента обнаружения, импорта
 * и коннекторов. Объекты пакета независимы: каждый идёт в своей точке сохранения, ошибка одного откатывает
 * только его, события публикуются только для успешных.
 *
 * <p>Идентификация: связка (источник, {@code externalId}), затем правила класса по порядку среди КЕ той же
 * корневой ветки классов. Приоритет: значение источника заменяет текущее, если атрибут пуст, получен от этого
 * же источника или от источника с приоритетом не выше (1 — самый доверенный, ручной ввод — 10). Иначе —
 * конфликт на экране «Сверка». Название, статус и среду меняют только источники доверенней ручного ввода.
 */
@Service
class IngestService {

    static final int MAX_ITEMS = 1000;
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final TypeReference<List<ItemResult>> RESULTS = new TypeReference<>() {
    };

    record Target(String externalId, UUID ciId) {
    }

    record ItemRelation(String typeCode, String direction, Target target) {
    }

    /** Объект источника. {@code relations = null} — связи не трогать, пустой список — снять связи источника. */
    record Item(String externalId, String classCode, String name, String status, String environment,
                Map<String, Object> attributes, List<ItemRelation> relations) {
    }

    record BatchRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,200}") String batchId,
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,200}") String runId,
            @JsonProperty("final") Boolean isFinal,
            @Valid @Size(max = MAX_ITEMS) List<Item> items,
            @Size(max = MAX_ITEMS) List<String> removed) {
    }

    record Warning(String code, String field) {
    }

    /**
     * @param outcome {@code created}, {@code updated}, {@code unchanged}, {@code conflict} (объект не применён,
     *                подходит под несколько КЕ), {@code error}, {@code removed}, {@code not_found}
     * @param conflicts сколько конфликтов сверки поднял объект
     */
    record ItemResult(String externalId, UUID ciId, String outcome, String code, String field, int conflicts,
                      List<Warning> warnings) {

        static ItemResult error(String externalId, String code, String field) {
            return new ItemResult(externalId, null, "error", code, field, 0, List.of());
        }

        ItemResult withWarnings(List<Warning> more) {
            List<Warning> all = new ArrayList<>(warnings);
            all.addAll(more);
            return new ItemResult(externalId, ciId, outcome, code, field, conflicts, all);
        }
    }

    record BatchResult(String batchId, String runId, @JsonProperty("final") boolean isFinal, String status, int received, int created,
                       int updated, int unchanged, int conflicts, int errors, int removed, List<ItemResult> results,
                       Instant createdAt) {
    }

    /** Состояние одного пакета. */
    private static final class Context {
        final SourceService.Source source;
        final ClassTree tree;
        final Map<String, CiClasses.RelationType> types = new HashMap<>();
        final Map<String, Integer> priorities = new HashMap<>();
        final UUID actor;
        final Instant now;
        final String runId;
        final Set<UUID> impacted = new LinkedHashSet<>();

        Context(SourceService.Source source, ClassTree tree, UUID actor, Instant now, String runId) {
            this.source = source;
            this.tree = tree;
            this.actor = actor;
            this.now = now;
            this.runId = runId;
        }

        String code() {
            return source.code();
        }

        boolean trusted() {
            return source.priority() <= SourceService.MANUAL_PRIORITY;
        }

        int priorityOf(String owner) {
            if (owner == null || CiService.MANUAL.equals(owner)) {
                return SourceService.MANUAL_PRIORITY;
            }
            return priorities.getOrDefault(owner, SourceService.UNKNOWN_PRIORITY);
        }
    }

    private final JdbcClient jdbc;
    private final SourceService sources;
    private final CiStore store;
    private final CiService cis;
    private final CiClasses classes;
    private final Attributes attributes;
    private final RelationService relations;
    private final CmdbJson json;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate savepoint;

    IngestService(JdbcClient jdbc, SourceService sources, CiStore store, CiService cis, CiClasses classes,
                  Attributes attributes, RelationService relations, CmdbJson json,
                  ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.store = store;
        this.cis = cis;
        this.classes = classes;
        this.attributes = attributes;
        this.relations = relations;
        this.json = json;
        this.events = events;
        this.savepoint = new TransactionTemplate(txManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ================================================================ пакеты

    @Transactional
    public BatchResult ingest(String sourceCode, BatchRequest req) {
        SourceService.Source source = sources.find(sourceCode)
                .orElseThrow(() -> ApiException.notFound("Источник данных"));
        if (!source.active()) {
            throw CmdbProblem.clash("source_inactive", "Источник «" + source.name() + "» отключён");
        }
        boolean isFinal = Boolean.TRUE.equals(req.isFinal());
        if (isFinal && req.runId() == null) {
            throw CmdbProblem.invalid("field_required", "runId",
                    "Последний пакет обхода (final) должен указать runId, чтобы найти невиденные объекты");
        }
        // Повтор пакета (агент не дождался ответа) возвращает сохранённый результат и ничего не меняет.
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtext('cmdb_batch:' || :source || ':' || :batch))")
                .param("source", source.id().toString()).param("batch", req.batchId()).query(Integer.class).single();
        var existing = batch(source, req.batchId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Context ctx = context(source, req.runId());
        List<Item> items = req.items() == null ? List.of() : req.items();
        List<ItemResult> results = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<Integer, UUID> applied = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            String ext = item == null || item.externalId() == null ? null : item.externalId().trim();
            if (ext != null && index.containsKey(ext)) {
                results.add(ItemResult.error(ext, "duplicate_external_id", "items[" + i + "].externalId"));
                continue;
            }
            if (ext != null) {
                index.put(ext, i);
            }
            ItemResult r = guarded(ext, sink -> process(ctx, item, null, sink));
            results.add(r);
            if (r.ciId() != null && !"conflict".equals(r.outcome())) {
                applied.put(i, r.ciId());
            }
        }
        // Связи — вторым проходом: цель может прийти в том же пакете позже источника.
        for (Map.Entry<Integer, UUID> e : applied.entrySet()) {
            Item item = items.get(e.getKey());
            if (item.relations() == null) {
                continue;
            }
            List<Warning> warnings = new ArrayList<>();
            try {
                savepoint.executeWithoutResult(st -> warnings.addAll(syncRelations(ctx, e.getValue(), item)));
            } catch (ApiException | DataAccessException ex) {
                log.warn("CMDB: связи объекта {} источника {} не применены", item.externalId(), ctx.code(), ex);
                warnings.add(new Warning("relations_failed", "relations"));
            }
            if (!warnings.isEmpty()) {
                results.set(e.getKey(), results.get(e.getKey()).withWarnings(warnings));
            }
        }
        int removed = 0;
        for (String raw : req.removed() == null ? List.<String>of() : req.removed()) {
            String ext = raw == null ? "" : raw.trim();
            ItemResult r = guarded(ext, sink -> remove(ctx, ext));
            removed += "removed".equals(r.outcome()) ? 1 : 0;
            results.add(r);
        }
        if (isFinal) {
            jdbc.sql("""
                    update ci_source_record set missing_since = :now
                     where source_id = :source and removed_at is null and missing_since is null
                       and last_run_id is distinct from :run""")
                    .param("now", Sql.ts(ctx.now)).param("source", source.id()).param("run", req.runId()).update();
        }
        if (!ctx.impacted.isEmpty()) {
            events.publishEvent(new CiImpactChanged(TenantContext.require(), ctx.now, ctx.actor, ctx.code(),
                    Set.copyOf(ctx.impacted), "source_sync"));
        }
        return save(ctx, req, isFinal, items.size(), removed, results);
    }

    @Transactional(readOnly = true)
    public BatchResult get(String sourceCode, String batchId) {
        SourceService.Source source = sources.get(sourceCode);
        return batch(source, batchId).orElseThrow(() -> ApiException.notFound("Пакет"));
    }

    /** Последние пакеты источника без результатов по объектам. */
    @Transactional(readOnly = true)
    public List<BatchResult> recent(String sourceCode, int limit) {
        SourceService.Source source = sources.get(sourceCode);
        return jdbc.sql("""
                        select batch_key, run_id, final, received, created, updated, unchanged, conflicts, errors,
                               removed, created_at
                          from ci_ingest_batch where source_id = :source order by created_at desc, id desc
                         limit :limit""")
                .param("source", source.id()).param("limit", Math.clamp(limit, 1, 100))
                .query((rs, n) -> new BatchResult(rs.getString("batch_key"), rs.getString("run_id"),
                        rs.getBoolean("final"), "done", rs.getInt("received"), rs.getInt("created"),
                        rs.getInt("updated"), rs.getInt("unchanged"), rs.getInt("conflicts"), rs.getInt("errors"),
                        rs.getInt("removed"), List.of(), Sql.instant(rs, "created_at")))
                .list();
    }

    // ================================================================ разбор конфликта дубликата

    /**
     * Применяет объект из конфликта {@code duplicate} к выбранной КЕ: связывает объект источника с ней и
     * обновляет её по правилам приоритета. Связи объекта применяются тоже.
     */
    void applyTo(UUID ciId, String sourceCode, Item item, Consumer<Object> sink) {
        SourceService.Source source = sources.find(sourceCode)
                .orElseThrow(() -> CmdbProblem.clash("source_missing", "Источник удалён"));
        Context ctx = context(source, null);
        process(ctx, item, ciId, sink);
        if (item.relations() != null) {
            syncRelations(ctx, ciId, item);
        }
        if (!ctx.impacted.isEmpty()) {
            sink.accept(new CiImpactChanged(TenantContext.require(), ctx.now, ctx.actor, ctx.code(),
                    Set.copyOf(ctx.impacted), "source_sync"));
        }
    }

    // ================================================================ объект

    private interface Work {
        ItemResult run(Consumer<Object> sink);
    }

    /** Объект в своей точке сохранения; события — только если объект применён целиком. */
    private ItemResult guarded(String ext, Work work) {
        List<Object> pending = new ArrayList<>();
        try {
            ItemResult r = savepoint.execute(st -> work.run(pending::add));
            pending.forEach(events::publishEvent);
            return r;
        } catch (CmdbProblem e) {
            Object field = e.properties().get("field");
            return ItemResult.error(ext, e.code(), field == null ? null : field.toString());
        } catch (ApiException e) {
            return ItemResult.error(ext, e.code(), null);
        } catch (DataAccessException e) {
            log.warn("CMDB: объект {} не загружен", ext, e);
            return ItemResult.error(ext, "storage_error", null);
        }
    }

    private ItemResult process(Context ctx, Item item, UUID forcedCiId, Consumer<Object> sink) {
        if (item == null) {
            throw CmdbProblem.invalid("field_required", "items", "Пустой объект");
        }
        String ext = item.externalId() == null ? "" : item.externalId().trim();
        if (ext.isEmpty() || ext.length() > 255) {
            throw CmdbProblem.invalid("field_required", "externalId", "externalId — от 1 до 255 символов");
        }
        ClassTree.ClassInfo cls = item.classCode() == null ? null : ctx.tree.byCode(item.classCode()).orElse(null);
        if (cls == null || !cls.active() || cls.isAbstract()) {
            throw CmdbProblem.invalid("unknown_class", "classCode", "Класс КЕ не найден или абстрактный");
        }
        String name = item.name() == null ? "" : item.name().trim();
        if (name.isEmpty() || name.length() > 255) {
            throw CmdbProblem.invalid("field_required", "name", "Название КЕ — от 1 до 255 символов");
        }
        attributes.rejectSecrets("name", name);
        if (item.status() != null && !item.status().matches(CiCommands.STATUS)) {
            throw CmdbProblem.invalid("invalid_field", "status", "status: planned, active, maintenance, retired");
        }
        if (item.environment() != null && !item.environment().matches(CiCommands.ENVIRONMENT)) {
            throw CmdbProblem.invalid("invalid_field", "environment", "Код среды: латиница, цифры и _");
        }
        Map<String, Object> incoming = new LinkedHashMap<>();
        if (item.attributes() != null) {
            item.attributes().forEach((k, v) -> {
                if (v != null && !"".equals(v)) {
                    incoming.put(k, v);
                }
            });
        }

        CiRow cur;
        if (forcedCiId != null) {
            cur = store.lock(forcedCiId).orElseThrow(() -> ApiException.notFound("КЕ"));
        } else {
            UUID linked = jdbc.sql("""
                            select ci_id from ci_source_record where source_id = :source and external_id = :ext""")
                    .param("source", ctx.source.id()).param("ext", ext).query(UUID.class).optional().orElse(null);
            cur = linked == null ? null : store.lock(linked).orElse(null);
            if (cur == null) {
                Map<String, Object> normalized = attributes.apply(optional(ctx.tree.effectiveAttributes(cls)),
                        Map.of(), incoming);
                List<UUID> candidates = identify(ctx.tree, cls, normalized);
                if (candidates.size() > 1) {
                    raiseDuplicate(ctx, ext, item, candidates);
                    return new ItemResult(ext, null, "conflict", "duplicate", null, 1, List.of());
                }
                if (candidates.size() == 1) {
                    cur = store.lock(candidates.getFirst()).orElseThrow();
                }
            }
        }
        ItemResult result = cur == null
                ? create(ctx, cls, ext, item, name, incoming, sink)
                : update(ctx, cur, ext, item, name, incoming, sink);
        link(ctx, ext, result.ciId());
        return result;
    }

    private ItemResult create(Context ctx, ClassTree.ClassInfo cls, String ext, Item item, String name,
                              Map<String, Object> incoming, Consumer<Object> sink) {
        Map<String, Object> attrs = attributes.apply(optional(ctx.tree.effectiveAttributes(cls)), Map.of(),
                incoming);
        Map<String, Object> owners = new LinkedHashMap<>();
        attrs.keySet().forEach(k -> owners.put(k, ctx.code()));
        CiRow row = new CiRow(Ids.newId(), cls.id(), cls.code(), cls.name(), cls.layer(), name,
                item.status() == null ? CiStatus.ACTIVE : CiStatus.fromCode(item.status()), item.environment(),
                CiCriticality.MEDIUM, null, "", attrs, owners, ctx.now, ctx.now, ctx.actor, ctx.now, ctx.actor, 0,
                null);
        store.insert(row);
        store.history(row.id(), "created", null, Map.of("name", row.name(), "class", cls.code(), "externalId", ext),
                ctx.code(), ctx.actor, ctx.now);
        sink.accept(new CiCreated(TenantContext.require(), ctx.now, ctx.actor, ctx.code(), row.ref()));
        return new ItemResult(ext, row.id(), "created", null, null, 0, List.of());
    }

    private ItemResult update(Context ctx, CiRow cur, String ext, Item item, String name,
                              Map<String, Object> incoming, Consumer<Object> sink) {
        ClassTree.ClassInfo cls = ctx.tree.byId(cur.classId()).orElseThrow();
        // Атрибуты проверяются по классу КЕ: класс мог поменять человек, источник его не меняет.
        Map<String, Object> normalized = attributes.apply(optional(ctx.tree.effectiveAttributes(cls)), Map.of(),
                incoming);
        Map<String, Object> attrs = new LinkedHashMap<>(cur.attributes());
        Map<String, Object> owners = new LinkedHashMap<>(cur.attributeSources());
        int conflicts = 0;
        for (Map.Entry<String, Object> e : normalized.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            Object current = attrs.get(key);
            String owner = owners.get(key) instanceof String s ? s : null;
            if (current == null || ctx.code().equals(owner) || ctx.source.priority() <= ctx.priorityOf(owner)) {
                attrs.put(key, value);
                owners.put(key, ctx.code());
                closeAttributeConflict(ctx, cur.id(), key);
            } else if (Objects.equals(current, value)) {
                closeAttributeConflict(ctx, cur.id(), key);
            } else if (raiseAttribute(ctx, cur.id(), ext, key, current, value, owner)) {
                conflicts++;
            }
        }
        String nextName = ctx.trusted() ? name : cur.name();
        CiStatus status = ctx.trusted() && item.status() != null ? CiStatus.fromCode(item.status()) : cur.status();
        String environment = cur.environment() == null || ctx.trusted() && item.environment() != null
                ? (item.environment() == null ? cur.environment() : item.environment())
                : cur.environment();
        CiRow next = new CiRow(cur.id(), cur.classId(), cur.classCode(), cur.className(), cur.layer(), nextName,
                status, environment, cur.criticality(), cur.ownerGroupId(), cur.description(), attrs, owners,
                ctx.now, cur.createdAt(), cur.createdBy(), ctx.now, ctx.actor, cur.version() + 1, cur.mergedInto());
        Set<String> changed = cis.recordChanges(cur, next, ctx.code(), ctx.actor, ctx.now);
        if (changed.isEmpty()) {
            // Только отметка актуальности (CFG-07) и владельцы значений — без новой версии и событий.
            jdbc.sql("""
                    update ci set last_seen_at = :now, attribute_sources = cast(:owners as jsonb) where id = :id""")
                    .param("now", Sql.ts(ctx.now)).param("owners", json.write(owners)).param("id", cur.id())
                    .update();
            return new ItemResult(ext, cur.id(), "unchanged", null, null, conflicts, List.of());
        }
        store.update(next);
        cis.publishChanges(cur, next, changed, ctx.code(), ctx.actor, ctx.now, sink);
        return new ItemResult(ext, cur.id(), "updated", null, null, conflicts, List.of());
    }

    /** КЕ по правилам идентификации класса среди КЕ той же корневой ветки (кроме слитых дубликатов). */
    private List<UUID> identify(ClassTree tree, ClassTree.ClassInfo cls, Map<String, Object> attrs) {
        List<List<String>> rules = tree.effectiveRules(cls);
        if (rules.isEmpty()) {
            return List.of();
        }
        ClassTree.ClassInfo root = tree.lineage(cls).getFirst();
        UUID[] branch = tree.withDescendants(List.of(root.code())).toArray(UUID[]::new);
        for (List<String> rule : rules) {
            if (rule.isEmpty() || !attrs.keySet().containsAll(rule)) {
                continue;
            }
            Map<String, Object> probe = new LinkedHashMap<>();
            rule.forEach(k -> probe.put(k, attrs.get(k)));
            List<UUID> found = jdbc.sql("""
                            select id from ci where class_id = any(:branch) and merged_into is null
                               and attributes @> cast(:probe as jsonb)
                             order by created_at, id limit 10""")
                    .param("branch", branch).param("probe", json.write(probe)).query(UUID.class).list();
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private void link(Context ctx, String ext, UUID ciId) {
        jdbc.sql("""
                insert into ci_source_record (id, tenant_id, source_id, external_id, ci_id, first_seen_at,
                                              last_seen_at, last_run_id)
                values (:id, :tenant, :source, :ext, :ci, :now, :now, :run)
                on conflict (source_id, external_id) do update
                   set ci_id = excluded.ci_id, last_seen_at = excluded.last_seen_at,
                       last_run_id = excluded.last_run_id, missing_since = null, removed_at = null""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("source", ctx.source.id())
                .param("ext", ext).param("ci", ciId).param("now", Sql.ts(ctx.now)).param("run", ctx.runId)
                .update();
    }

    private ItemResult remove(Context ctx, String ext) {
        UUID ciId = jdbc.sql("""
                        update ci_source_record set removed_at = :now
                         where source_id = :source and external_id = :ext and removed_at is null
                        returning ci_id""")
                .param("now", Sql.ts(ctx.now)).param("source", ctx.source.id()).param("ext", ext)
                .query(UUID.class).optional().orElse(null);
        if (ciId == null) {
            return new ItemResult(ext, null, "not_found", null, null, 0, List.of());
        }
        closeSourceRelations(ctx, ciId, Set.of());
        return new ItemResult(ext, ciId, "removed", null, null, 0, List.of());
    }

    // ================================================================ связи

    private record Triple(UUID typeId, UUID source, UUID target) {
    }

    /** Связи объекта заменяют связи, которые этот источник раньше объявил у этой КЕ; остальные не трогаются. */
    private List<Warning> syncRelations(Context ctx, UUID ciId, Item item) {
        List<Warning> warnings = new ArrayList<>();
        Set<Triple> desired = new LinkedHashSet<>();
        for (int i = 0; i < item.relations().size(); i++) {
            ItemRelation rel = item.relations().get(i);
            String field = "relations[" + i + "]";
            CiClasses.RelationType type = rel == null ? null : ctx.types.get(rel.typeCode());
            if (type == null || !type.active()) {
                warnings.add(new Warning("unknown_relation_type", field + ".typeCode"));
                continue;
            }
            UUID target = resolveTarget(ctx, rel.target());
            if (target == null) {
                warnings.add(new Warning("unknown_target", field + ".target"));
                continue;
            }
            if (target.equals(ciId)) {
                warnings.add(new Warning("self_relation", field + ".target"));
                continue;
            }
            boolean incoming = "incoming".equals(rel.direction());
            desired.add(new Triple(type.id(), incoming ? target : ciId, incoming ? ciId : target));
        }
        Set<Triple> kept = closeSourceRelations(ctx, ciId, desired);
        Map<UUID, CiClasses.RelationType> byId = new HashMap<>();
        ctx.types.values().forEach(t -> byId.put(t.id(), t));
        for (Triple t : desired) {
            if (kept.contains(t)) {
                continue;
            }
            Integer exists = jdbc.sql("""
                            select count(*) from ci_relation
                             where valid_to is null and type_id = :type and source_id = :s and target_id = :t""")
                    .param("type", t.typeId()).param("s", t.source()).param("t", t.target())
                    .query(Integer.class).single();
            if (exists > 0) {
                continue; // такую связь уже завёл человек или другой источник
            }
            relations.insert(t.typeId(), t.source(), t.target(), ctx.code(), ciId, ctx.actor, ctx.now);
            CiClasses.RelationType type = byId.get(t.typeId());
            Map<String, Object> value = Map.of("type", type.code(), "source", t.source(), "target", t.target());
            store.history(t.source(), "relation", null, value, ctx.code(), ctx.actor, ctx.now);
            store.history(t.target(), "relation", null, value, ctx.code(), ctx.actor, ctx.now);
            if (type.propagates()) {
                ctx.impacted.add(t.source());
                ctx.impacted.add(t.target());
            }
        }
        return warnings;
    }

    /** Закрывает связи источника у КЕ, которых нет в {@code keep}; возвращает оставшиеся. */
    private Set<Triple> closeSourceRelations(Context ctx, UUID ciId, Set<Triple> keep) {
        record Row(UUID id, Triple triple) {
        }
        List<Row> existing = jdbc.sql("""
                        select id, type_id, source_id, target_id from ci_relation
                         where valid_to is null and origin = :origin and origin_ci_id = :ci for update""")
                .param("origin", ctx.code()).param("ci", ciId)
                .query((rs, n) -> new Row(Sql.uuid(rs, "id"), new Triple(Sql.uuid(rs, "type_id"),
                        Sql.uuid(rs, "source_id"), Sql.uuid(rs, "target_id"))))
                .list();
        Map<UUID, CiClasses.RelationType> byId = new HashMap<>();
        ctx.types.values().forEach(t -> byId.put(t.id(), t));
        Set<Triple> kept = new LinkedHashSet<>();
        for (Row r : existing) {
            if (keep.contains(r.triple())) {
                kept.add(r.triple());
                continue;
            }
            jdbc.sql("""
                    update ci_relation set valid_to = :now, updated_at = :now, updated_by = :actor,
                                           version = version + 1
                     where id = :id""")
                    .param("id", r.id()).param("now", Sql.ts(ctx.now)).param("actor", ctx.actor).update();
            CiClasses.RelationType type = byId.get(r.triple().typeId());
            Map<String, Object> value = Map.of("type", type == null ? "" : type.code(), "source",
                    r.triple().source(), "target", r.triple().target());
            store.history(r.triple().source(), "relation", value, null, ctx.code(), ctx.actor, ctx.now);
            store.history(r.triple().target(), "relation", value, null, ctx.code(), ctx.actor, ctx.now);
            if (type != null && type.propagates()) {
                ctx.impacted.add(r.triple().source());
                ctx.impacted.add(r.triple().target());
            }
        }
        return kept;
    }

    private UUID resolveTarget(Context ctx, Target target) {
        if (target == null) {
            return null;
        }
        if (target.ciId() != null) {
            return store.find(target.ciId()).filter(r -> r.mergedInto() == null).map(CiRow::id).orElse(null);
        }
        if (target.externalId() == null || target.externalId().isBlank()) {
            return null;
        }
        return jdbc.sql("""
                        select ci_id from ci_source_record
                         where source_id = :source and external_id = :ext and removed_at is null""")
                .param("source", ctx.source.id()).param("ext", target.externalId().trim())
                .query(UUID.class).optional().orElse(null);
    }

    // ================================================================ конфликты

    /**
     * Поднимает или обновляет открытый конфликт атрибута. Отклонённое («Игнорировать») значение повторно
     * конфликт не поднимает, пока источник не пришлёт другое.
     */
    private boolean raiseAttribute(Context ctx, UUID ciId, String ext, String key, Object current, Object proposed,
                                   String owner) {
        Integer ignored = jdbc.sql("""
                        select count(*) from ci_conflict
                         where kind = 'attribute' and status = 'ignored' and source_id = :source and ci_id = :ci
                           and attribute = :key and proposed_value = cast(:proposed as jsonb)""")
                .param("source", ctx.source.id()).param("ci", ciId).param("key", key)
                .param("proposed", json.write(proposed)).query(Integer.class).single();
        if (ignored > 0) {
            closeAttributeConflict(ctx, ciId, key);
            return false;
        }
        jdbc.sql("""
                insert into ci_conflict (id, tenant_id, kind, status, source_id, external_id, ci_id, attribute,
                                         current_value, proposed_value, current_source, created_at, updated_at,
                                         version)
                values (:id, :tenant, 'attribute', 'open', :source, :ext, :ci, :key, cast(:current as jsonb),
                        cast(:proposed as jsonb), :owner, :now, :now, 0)
                on conflict (source_id, ci_id, attribute) where kind = 'attribute' and status = 'open' do update
                   set current_value = excluded.current_value, proposed_value = excluded.proposed_value,
                       current_source = excluded.current_source, external_id = excluded.external_id,
                       updated_at = excluded.updated_at, version = ci_conflict.version + 1""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require())
                .param("source", ctx.source.id()).param("ext", ext).param("ci", ciId).param("key", key)
                .param("current", json.write(current)).param("proposed", json.write(proposed))
                .param("owner", owner == null ? CiService.MANUAL : owner).param("now", Sql.ts(ctx.now))
                .update();
        return true;
    }

    /** Значение совпало или применено — открытый конфликт по атрибуту больше не нужен. */
    private void closeAttributeConflict(Context ctx, UUID ciId, String key) {
        jdbc.sql("""
                update ci_conflict set status = 'dismissed', resolved_at = :now, updated_at = :now,
                                       version = version + 1
                 where kind = 'attribute' and status = 'open' and source_id = :source and ci_id = :ci
                   and attribute = :key""")
                .param("now", Sql.ts(ctx.now)).param("source", ctx.source.id()).param("ci", ciId).param("key", key)
                .update();
    }

    private void raiseDuplicate(Context ctx, String ext, Item item, List<UUID> candidates) {
        jdbc.sql("""
                insert into ci_conflict (id, tenant_id, kind, status, source_id, external_id, candidate_ids, item,
                                         created_at, updated_at, version)
                values (:id, :tenant, 'duplicate', 'open', :source, :ext, :candidates, cast(:item as jsonb),
                        :now, :now, 0)
                on conflict (source_id, external_id) where kind = 'duplicate' and status = 'open' do update
                   set candidate_ids = excluded.candidate_ids, item = excluded.item,
                       updated_at = excluded.updated_at, version = ci_conflict.version + 1""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("source", ctx.source.id())
                .param("ext", ext).param("candidates", candidates.toArray(UUID[]::new))
                .param("item", json.write(item)).param("now", Sql.ts(ctx.now))
                .update();
    }

    // ================================================================ общее

    private Context context(SourceService.Source source, String runId) {
        Context ctx = new Context(source, classes.tree(), CurrentActor.userId().orElse(null), Sql.now(), runId);
        classes.relationTypes().forEach(t -> ctx.types.put(t.code(), t));
        sources.list().forEach(s -> ctx.priorities.put(s.code(), s.priority()));
        return ctx;
    }

    /** Источник не обязан знать все обязательные атрибуты: неполнота видна в показателях качества. */
    private static List<AttributeDef> optional(List<AttributeDef> schema) {
        return schema.stream().map(d -> new AttributeDef(d.code(), d.name(), d.type(), false, d.options()))
                .toList();
    }

    private java.util.Optional<BatchResult> batch(SourceService.Source source, String batchId) {
        return jdbc.sql("""
                        select batch_key, run_id, final, received, created, updated, unchanged, conflicts, errors,
                               removed, results::text as results, created_at
                          from ci_ingest_batch where source_id = :source and batch_key = :batch""")
                .param("source", source.id()).param("batch", batchId)
                .query((rs, n) -> new BatchResult(rs.getString("batch_key"), rs.getString("run_id"),
                        rs.getBoolean("final"), "done", rs.getInt("received"), rs.getInt("created"),
                        rs.getInt("updated"), rs.getInt("unchanged"), rs.getInt("conflicts"), rs.getInt("errors"),
                        rs.getInt("removed"), json.read(rs.getString("results"), RESULTS),
                        Sql.instant(rs, "created_at")))
                .optional();
    }

    private BatchResult save(Context ctx, BatchRequest req, boolean isFinal, int received, int removed,
                             List<ItemResult> results) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int conflicts = 0;
        int errors = 0;
        for (ItemResult r : results) {
            switch (r.outcome()) {
                case "created" -> created++;
                case "updated" -> updated++;
                case "unchanged" -> unchanged++;
                case "error" -> errors++;
                default -> {
                }
            }
            conflicts += r.conflicts();
        }
        BatchResult out = new BatchResult(req.batchId(), req.runId(), isFinal, "done", received, created, updated,
                unchanged, conflicts, errors, removed, results, ctx.now);
        jdbc.sql("""
                insert into ci_ingest_batch (id, tenant_id, source_id, batch_key, run_id, final, received, created,
                                             updated, unchanged, conflicts, errors, removed, results, created_at,
                                             created_by)
                values (:id, :tenant, :source, :batch, :run, :final, :received, :created, :updated, :unchanged,
                        :conflicts, :errors, :removed, cast(:results as jsonb), :now, :actor)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("source", ctx.source.id())
                .param("batch", req.batchId()).param("run", req.runId()).param("final", isFinal)
                .param("received", received).param("created", created).param("updated", updated)
                .param("unchanged", unchanged).param("conflicts", conflicts).param("errors", errors)
                .param("removed", removed).param("results", json.write(results)).param("now", Sql.ts(ctx.now))
                .param("actor", ctx.actor)
                .update();
        return out;
    }
}
