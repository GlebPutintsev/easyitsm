package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiCriticality;
import ru.easyitsm.cmdb.api.CiDirectory;
import ru.easyitsm.cmdb.api.CiLayer;
import ru.easyitsm.cmdb.api.CiRef;
import ru.easyitsm.cmdb.api.CiSearch;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.events.CiCreated;
import ru.easyitsm.cmdb.events.CiImpactChanged;
import ru.easyitsm.cmdb.events.CiStatusChanged;
import ru.easyitsm.cmdb.events.CiUpdated;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** КЕ: чтение для других модулей ({@link CiDirectory}), список, карточка, создание и изменение через REST. */
@Service
class CiService implements CiDirectory {

    static final String MANUAL = "manual";

    /** Фильтры списка КЕ (экран 10.13). Пустые значения не фильтруют. */
    record Filter(String q, List<String> classCodes, List<String> layers, List<String> statuses,
                  List<String> environments, List<String> criticalities, UUID ownerGroupId, boolean includeRetired) {
    }

    private final CiStore store;
    private final CiClasses classes;
    private final Attributes attributes;
    private final UserDirectory directory;
    private final ApplicationEventPublisher events;

    CiService(CiStore store, CiClasses classes, Attributes attributes, UserDirectory directory,
              ApplicationEventPublisher events) {
        this.store = store;
        this.classes = classes;
        this.attributes = attributes;
        this.directory = directory;
        this.events = events;
    }

    // ================================================================ CiDirectory

    @Override
    @Transactional(readOnly = true)
    public Optional<CiRef> find(UUID ciId) {
        return store.find(ciId).map(CiRow::ref);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CiRef> findAll(Collection<UUID> ciIds) {
        if (ciIds.size() > 1000) {
            throw new IllegalArgumentException("Не больше 1000 КЕ за вызов");
        }
        return store.findAll(ciIds).stream().map(CiRow::ref).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CiRef> search(CiSearch query) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        String text = query.text() == null ? "" : query.text().trim();
        UUID exactId = parseUuid(text);
        if (exactId != null) {
            where.append(" and c.id = :exactId");
            params.put("exactId", exactId);
        } else if (!text.isEmpty()) {
            where.append(" and lower(c.name) like :like escape '\\'");
            params.put("like", Sql.likeContains(text));
        }
        appendClassFilters(where, params, query.classCodes(),
                query.layers().stream().map(CiLayer::code).toList());
        if (!query.includeRetired()) {
            where.append(" and c.status <> 'retired'");
        }
        params.put("text", text.toLowerCase());
        params.put("limit", query.limit());
        return store.query(where + """
                         order by (lower(c.name) = :text) desc, (lower(c.name) like :text || '%') desc,
                                  lower(c.name), c.id
                         limit :limit""", params)
                .stream().map(CiRow::ref).toList();
    }

    // ================================================================ REST

    @Transactional(readOnly = true)
    public CursorPage<CiView> list(Filter f, PageParams page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" and lower(c.name) like :like escape '\\'");
            params.put("like", Sql.likeContains(f.q().trim()));
        }
        appendClassFilters(where, params, f.classCodes(), f.layers());
        if (!f.statuses().isEmpty()) {
            where.append(" and c.status = any(:statuses)");
            params.put("statuses", f.statuses().toArray(String[]::new));
        } else if (!f.includeRetired()) {
            where.append(" and c.status <> 'retired'");
        }
        if (!f.environments().isEmpty()) {
            where.append(" and c.environment = any(:environments)");
            params.put("environments", f.environments().toArray(String[]::new));
        }
        if (!f.criticalities().isEmpty()) {
            where.append(" and c.criticality = any(:criticalities)");
            params.put("criticalities", f.criticalities().toArray(String[]::new));
        }
        if (f.ownerGroupId() != null) {
            where.append(" and c.owner_group_id = :owner");
            params.put("owner", f.ownerGroupId());
        }
        KeysetCursor after = page.keyset();
        if (after != null) {
            where.append(" and (lower(c.name), c.id) > (:afterName, :afterId)");
            params.put("afterName", after.sortValue());
            params.put("afterId", after.id());
        }
        params.put("limit", page.limit() + 1);
        List<CiRow> rows = store.query(where + " order by lower(c.name), c.id limit :limit", params);
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.name().toLowerCase(), r.id()))
                .map(CiView::of);
    }

    @Transactional(readOnly = true)
    public CiView get(UUID id) {
        return CiView.of(require(id));
    }

    @Transactional
    public CiView create(CiCommands.Create cmd) {
        ClassTree tree = classes.tree();
        ClassTree.ClassInfo cls = concreteClass(tree, cmd.classCode());
        attributes.rejectSecrets("name", cmd.name());
        attributes.rejectSecrets("description", cmd.description());
        checkGroup(cmd.ownerGroupId());
        Map<String, Object> attrs = attributes.apply(tree.effectiveAttributes(cls), Map.of(), cmd.attributes());
        Map<String, Object> sources = new LinkedHashMap<>();
        attrs.keySet().forEach(k -> sources.put(k, MANUAL));
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        CiRow row = new CiRow(Ids.newId(), cls.id(), cls.code(), cls.name(), cls.layer(), cmd.name().trim(),
                cmd.status() == null ? CiStatus.ACTIVE : CiStatus.fromCode(cmd.status()), cmd.environment(),
                cmd.criticality() == null ? CiCriticality.MEDIUM : CiCriticality.fromCode(cmd.criticality()),
                cmd.ownerGroupId(), cmd.description() == null ? "" : cmd.description(), attrs, sources, null, now,
                actor, now, actor, 0);
        store.insert(row);
        store.history(row.id(), "created", null, Map.of("name", row.name(), "class", cls.code()), MANUAL, actor,
                now);
        events.publishEvent(new CiCreated(TenantContext.require(), now, actor, MANUAL, row.ref()));
        return CiView.of(row);
    }

    @Transactional
    public CiView update(UUID id, String ifMatch, CiCommands.Update cmd) {
        CiRow cur = store.lock(id).orElseThrow(() -> ApiException.notFound("КЕ"));
        ETags.requireMatch(ifMatch, cur.version());
        ClassTree tree = classes.tree();
        ClassTree.ClassInfo cls = tree.byId(cur.classId()).orElseThrow();
        Map<String, Object> f = cmd.fields();

        String name = cur.name();
        if (cmd.has("name")) {
            String v = string(f, "name");
            if (v == null || v.isBlank() || v.length() > 255) {
                throw CmdbProblem.invalid("invalid_field", "name", "Название КЕ — от 1 до 255 символов");
            }
            attributes.rejectSecrets("name", v);
            name = v.trim();
        }
        if (cmd.has("classCode")) {
            String code = string(f, "classCode");
            cls = concreteClass(tree, code);
        }
        CiStatus status = cur.status();
        if (cmd.has("status")) {
            status = parse(string(f, "status"), "status", CiCommands.STATUS, CiStatus::fromCode);
        }
        String environment = cur.environment();
        if (cmd.has("environment")) {
            environment = string(f, "environment");
            if (environment != null && !environment.matches(CiCommands.ENVIRONMENT)) {
                throw CmdbProblem.invalid("invalid_field", "environment", "Код среды: латиница, цифры и _");
            }
        }
        CiCriticality criticality = cur.criticality();
        if (cmd.has("criticality")) {
            criticality = parse(string(f, "criticality"), "criticality", CiCommands.CRITICALITY,
                    CiCriticality::fromCode);
        }
        UUID owner = cur.ownerGroupId();
        if (cmd.has("ownerGroupId")) {
            String v = string(f, "ownerGroupId");
            owner = v == null ? null : uuid(v, "ownerGroupId");
            if (!Objects.equals(owner, cur.ownerGroupId())) {
                checkGroup(owner);
            }
        }
        String description = cur.description();
        if (cmd.has("description")) {
            description = Objects.requireNonNullElse(string(f, "description"), "");
            if (description.length() > 10_000) {
                throw CmdbProblem.invalid("invalid_field", "description", "Описание длиннее 10 000 символов");
            }
            attributes.rejectSecrets("description", description);
        }
        Map<String, Object> changes = null;
        if (cmd.has("attributes")) {
            if (!(f.get("attributes") instanceof Map<?, ?> m)) {
                throw CmdbProblem.invalid("invalid_field", "attributes", "attributes — объект");
            }
            changes = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                changes.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        // Смена класса оставляет только атрибуты нового класса, которые прошли проверку.
        Map<String, Object> base = new LinkedHashMap<>(cur.attributes());
        if (!cls.id().equals(cur.classId())) {
            Set<String> allowed = new LinkedHashSet<>();
            tree.effectiveAttributes(cls).forEach(a -> allowed.add(a.code()));
            base.keySet().retainAll(allowed);
        }
        Map<String, Object> attrs = attributes.apply(tree.effectiveAttributes(cls), base, changes);
        Map<String, Object> sources = new LinkedHashMap<>(cur.attributeSources());
        sources.keySet().retainAll(attrs.keySet());
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (!Objects.equals(e.getValue(), cur.attributes().get(e.getKey()))) {
                sources.put(e.getKey(), MANUAL);
            }
        }

        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        CiRow next = new CiRow(cur.id(), cls.id(), cls.code(), cls.name(), cls.layer(), name, status, environment,
                criticality, owner, description, attrs, sources, cur.lastSeenAt(), cur.createdAt(), cur.createdBy(),
                now, actor, cur.version() + 1);
        Set<String> changed = recordChanges(cur, next, MANUAL, actor, now);
        if (changed.isEmpty()) {
            return CiView.of(cur);
        }
        store.update(next);
        publishChanges(cur, next, changed, MANUAL, actor, now);
        return CiView.of(next);
    }

    // ================================================================ общее

    CiRow require(UUID id) {
        // Чужая КЕ отсекается RLS и выглядит как отсутствующая: 404 (AT-01).
        return store.find(id).orElseThrow(() -> ApiException.notFound("КЕ"));
    }

    /** Пишет историю по изменённым полям и возвращает их коды для события {@link CiUpdated}. */
    Set<String> recordChanges(CiRow a, CiRow b, String source, UUID actor, Instant at) {
        Set<String> changed = new LinkedHashSet<>();
        diff(changed, a.id(), "name", a.name(), b.name(), source, actor, at);
        diff(changed, a.id(), "class", a.classCode(), b.classCode(), source, actor, at);
        diff(changed, a.id(), "status", a.status().code(), b.status().code(), source, actor, at);
        diff(changed, a.id(), "environment", a.environment(), b.environment(), source, actor, at);
        diff(changed, a.id(), "criticality", a.criticality().code(), b.criticality().code(), source, actor, at);
        diff(changed, a.id(), "owner_group", a.ownerGroupId(), b.ownerGroupId(), source, actor, at);
        diff(changed, a.id(), "description", a.description(), b.description(), source, actor, at);
        Set<String> keys = new LinkedHashSet<>(a.attributes().keySet());
        keys.addAll(b.attributes().keySet());
        for (String k : keys) {
            diff(changed, a.id(), "attr." + k, a.attributes().get(k), b.attributes().get(k), source, actor, at);
        }
        return changed;
    }

    void publishChanges(CiRow before, CiRow after, Set<String> changed, String source, UUID actor, Instant at) {
        UUID tenant = TenantContext.require();
        Set<String> other = new LinkedHashSet<>(changed);
        other.remove("status");
        if (!other.isEmpty()) {
            events.publishEvent(new CiUpdated(tenant, at, actor, source, after.ref(), other));
        }
        if (changed.contains("status")) {
            events.publishEvent(new CiStatusChanged(tenant, at, actor, source, after.ref(), before.status()));
            if (before.status() == CiStatus.RETIRED || after.status() == CiStatus.RETIRED) {
                events.publishEvent(new CiImpactChanged(tenant, at, actor, source, Set.of(after.id()),
                        "status_changed"));
            }
        }
    }

    private void diff(Set<String> changed, UUID ciId, String field, Object a, Object b, String source, UUID actor,
                      Instant at) {
        if (!Objects.equals(a, b)) {
            changed.add(field);
            store.history(ciId, field, a, b, source, actor, at);
        }
    }

    private void appendClassFilters(StringBuilder where, Map<String, Object> params, Collection<String> classCodes,
                                    Collection<String> layers) {
        if (!classCodes.isEmpty()) {
            Set<UUID> ids = classes.tree().withDescendants(classCodes);
            where.append(" and c.class_id = any(:classIds)");
            params.put("classIds", ids.toArray(UUID[]::new));
        }
        if (!layers.isEmpty()) {
            where.append(" and k.layer = any(:layers)");
            params.put("layers", layers.toArray(String[]::new));
        }
    }

    private ClassTree.ClassInfo concreteClass(ClassTree tree, String code) {
        ClassTree.ClassInfo cls = code == null ? null : tree.byCode(code).orElse(null);
        if (cls == null || !cls.active()) {
            throw CmdbProblem.invalid("unknown_class", "classCode", "Класс КЕ не найден");
        }
        if (cls.isAbstract()) {
            throw CmdbProblem.invalid("abstract_class", "classCode",
                    "Класс «" + cls.name() + "» служит для группировки, выберите подкласс");
        }
        return cls;
    }

    private void checkGroup(UUID groupId) {
        if (groupId != null && directory.findGroup(groupId).isEmpty()) {
            throw CmdbProblem.invalid("unknown_group", "ownerGroupId", "Группа не найдена");
        }
    }

    private static String string(Map<String, Object> f, String name) {
        Object v = f.get(name);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw CmdbProblem.invalid("invalid_field", name, name + " — строка");
        }
        return s;
    }

    private static <T> T parse(String value, String field, String allowed, java.util.function.Function<String, T> f) {
        if (value == null || !value.matches(allowed)) {
            throw CmdbProblem.invalid("invalid_field", field, field + ": " + allowed.replace("|", ", "));
        }
        return f.apply(value);
    }

    private static UUID uuid(String value, String field) {
        UUID id = parseUuid(value);
        if (id == null) {
            throw CmdbProblem.invalid("invalid_field", field, field + " — UUID");
        }
        return id;
    }

    static UUID parseUuid(String value) {
        if (value == null || value.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static List<String> split(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                for (String part : v.split(",")) {
                    if (!part.isBlank()) {
                        out.add(part.trim());
                    }
                }
            }
        }
        return out;
    }
}
