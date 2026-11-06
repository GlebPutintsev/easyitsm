package ru.easyitsm.cmdb.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.cmdb.api.CiCriticality;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * История КЕ и состояние на дату (CFG-06). Состояние восстанавливается от текущего: записи истории позже
 * заданного момента откатываются по старому значению, от новых к старым.
 */
@Service
class CiHistoryService {

    record Entry(UUID id, String field, Object oldValue, Object newValue, String source, UUID actorId, Instant at) {
    }

    private final JdbcClient jdbc;
    private final CmdbJson json;
    private final CiService cis;
    private final CiClasses classes;

    CiHistoryService(JdbcClient jdbc, CmdbJson json, CiService cis, CiClasses classes) {
        this.jdbc = jdbc;
        this.json = json;
        this.cis = cis;
        this.classes = classes;
    }

    @Transactional(readOnly = true)
    public CursorPage<Entry> history(UUID ciId, PageParams page) {
        cis.require(ciId);
        KeysetCursor after = page.keyset();
        Map<String, Object> params = new HashMap<>();
        params.put("ci", ciId);
        params.put("limit", page.limit() + 1);
        String keyset = "";
        if (after != null) {
            keyset = " and (at, id) < (:afterAt, :afterId)";
            params.put("afterAt", Sql.ts(Instant.parse(after.sortValue())));
            params.put("afterId", after.id());
        }
        List<Entry> rows = jdbc.sql("""
                        select id, field, old_value::text as old_value, new_value::text as new_value, source, actor_id, at
                          from ci_history where ci_id = :ci""" + keyset + " order by at desc, id desc limit :limit")
                .params(params)
                .query((rs, n) -> new Entry(Sql.uuid(rs, "id"), rs.getString("field"),
                        value(rs.getString("old_value")), value(rs.getString("new_value")), rs.getString("source"),
                        Sql.uuid(rs, "actor_id"), Sql.instant(rs, "at")))
                .list();
        return CursorPage.of(rows, page.limit(), e -> new KeysetCursor(e.at().toString(), e.id()));
    }

    /** Карточка КЕ на момент {@code at}; КЕ, созданная позже, — 404. */
    @Transactional(readOnly = true)
    public CiView asOf(UUID ciId, Instant at) {
        CiRow cur = cis.require(ciId);
        if (at.isBefore(cur.createdAt())) {
            throw ApiException.notFound("КЕ на эту дату");
        }
        List<Entry> later = jdbc.sql("""
                        select id, field, old_value::text as old_value, new_value::text as new_value, source, actor_id, at
                          from ci_history where ci_id = :ci and at > :at order by at desc, id desc""")
                .param("ci", ciId).param("at", Sql.ts(at))
                .query((rs, n) -> new Entry(Sql.uuid(rs, "id"), rs.getString("field"),
                        value(rs.getString("old_value")), value(rs.getString("new_value")), rs.getString("source"),
                        Sql.uuid(rs, "actor_id"), Sql.instant(rs, "at")))
                .list();
        String name = cur.name();
        String classCode = cur.classCode();
        CiStatus status = cur.status();
        String environment = cur.environment();
        CiCriticality criticality = cur.criticality();
        UUID owner = cur.ownerGroupId();
        String description = cur.description();
        Map<String, Object> attrs = new LinkedHashMap<>(cur.attributes());
        Instant updatedAt = cur.updatedAt();
        UUID updatedBy = cur.updatedBy();
        for (Entry e : later) {
            Object old = e.oldValue();
            switch (e.field()) {
                case "name" -> name = (String) old;
                case "class" -> classCode = (String) old;
                case "status" -> status = CiStatus.fromCode((String) old);
                case "environment" -> environment = (String) old;
                case "criticality" -> criticality = CiCriticality.fromCode((String) old);
                case "owner_group" -> owner = old == null ? null : UUID.fromString((String) old);
                case "description" -> description = old == null ? "" : (String) old;
                default -> {
                    if (e.field().startsWith("attr.")) {
                        String key = e.field().substring(5);
                        if (old == null) {
                            attrs.remove(key);
                        } else {
                            attrs.put(key, old);
                        }
                    }
                }
            }
        }
        // Последнее изменение до момента at: оно и есть «обновлено» в восстановленной карточке.
        Entry last = jdbc.sql("""
                        select id, field, null as old_value, null as new_value, source, actor_id, at
                          from ci_history where ci_id = :ci and at <= :at and field <> 'relation' order by at desc, id desc limit 1""")
                .param("ci", ciId).param("at", Sql.ts(at))
                .query((rs, n) -> new Entry(Sql.uuid(rs, "id"), rs.getString("field"), null, null,
                        rs.getString("source"), Sql.uuid(rs, "actor_id"), Sql.instant(rs, "at")))
                .optional().orElse(null);
        if (later.stream().anyMatch(e -> !"relation".equals(e.field())) && last != null) {
            updatedAt = last.at();
            updatedBy = last.actorId();
        }
        ClassTree.ClassInfo cls = classes.tree().byCode(classCode).orElse(null);
        String className = cls == null ? classCode : cls.name();
        String layer = cls == null ? cur.layer().code() : cls.layer().code();
        return new CiView(cur.id(), name, classCode, className, layer, status.code(), environment, criticality.code(),
                owner, description, attrs, Map.of(), null, cur.createdAt(), cur.createdBy(), updatedAt, updatedBy,
                cur.version() - later.stream().filter(e -> !"relation".equals(e.field())).map(Entry::at)
                        .distinct().count(), null);
    }

    private Object value(String text) {
        if (text == null) {
            return null;
        }
        return json.read(text);
    }
}
