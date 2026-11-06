package ru.easyitsm.cmdb.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Показатели качества CMDB (CFG-07) по КЕ в эксплуатации (не выведенным):
 * <ul>
 *   <li>полнота — заполнены группа-владелец и обязательные атрибуты класса;</li>
 *   <li>актуальность — источник видел КЕ за последние {@value #STALE_DAYS} дней, а КЕ без источников
 *       менялась за последние {@value #MANUAL_STALE_DAYS} дней; КЕ, которую все её источники больше не видят
 *       (удалена или не пришла в последнем полном обходе), неактуальна сразу;</li>
 *   <li>сироты — КЕ без единой активной связи;</li>
 *   <li>дубликаты — КЕ с одинаковым идентификатором (атрибуты из правил идентификации) или одинаковым именем
 *       в одном классе.</li>
 * </ul>
 */
@Service
class QualityService {

    static final int STALE_DAYS = 30;
    static final int MANUAL_STALE_DAYS = 365;
    static final int LIST_LIMIT = 200;

    record Summary(int total, int incomplete, int stale, int orphans, int duplicateGroups, int duplicates,
                   int score) {
    }

    /** Группа дубликатов: по какому признаку и какие КЕ. */
    record DuplicateGroup(String key, String value, List<CmdbController.CiRefView> cis) {
    }

    private final JdbcClient jdbc;
    private final CiStore store;
    private final CiClasses classes;

    QualityService(JdbcClient jdbc, CiStore store, CiClasses classes) {
        this.jdbc = jdbc;
        this.store = store;
        this.classes = classes;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        int total = jdbc.sql("select count(*) from ci where status <> 'retired'").query(Integer.class).single();
        List<UUID> incomplete = incompleteIds(Integer.MAX_VALUE);
        List<UUID> stale = staleIds(Integer.MAX_VALUE);
        List<UUID> orphans = orphanIds(Integer.MAX_VALUE);
        List<DuplicateGroup> dups = duplicates(Integer.MAX_VALUE);
        int dupCis = (int) dups.stream().flatMap(g -> g.cis().stream()).map(CmdbController.CiRefView::id)
                .distinct().count();
        // Оценка 0–100: доля КЕ без замечаний.
        Set<UUID> bad = new LinkedHashSet<>();
        bad.addAll(incomplete);
        bad.addAll(stale);
        bad.addAll(orphans);
        dups.forEach(g -> g.cis().forEach(c -> bad.add(c.id())));
        int score = total == 0 ? 100 : (int) Math.round(100.0 * (total - bad.size()) / total);
        return new Summary(total, incomplete.size(), stale.size(), orphans.size(), dups.size(), dupCis, score);
    }

    @Transactional(readOnly = true)
    public List<CmdbController.CiRefView> issues(String kind) {
        List<UUID> ids = switch (kind) {
            case "incomplete" -> incompleteIds(LIST_LIMIT);
            case "stale" -> staleIds(LIST_LIMIT);
            case "orphan" -> orphanIds(LIST_LIMIT);
            default -> throw CmdbProblem.invalid("invalid_field", "kind", "kind: incomplete, stale, orphan");
        };
        return store.findAll(ids).stream().map(r -> CmdbController.CiRefView.of(r.ref())).toList();
    }

    @Transactional(readOnly = true)
    public List<DuplicateGroup> duplicateGroups() {
        return duplicates(LIST_LIMIT);
    }

    private List<UUID> incompleteIds(int limit) {
        ClassTree tree = classes.tree();
        Map<UUID, List<String>> required = new LinkedHashMap<>();
        for (ClassTree.ClassInfo c : tree.all()) {
            List<String> codes = tree.effectiveAttributes(c).stream().filter(AttributeDef::required)
                    .map(AttributeDef::code).toList();
            if (!codes.isEmpty()) {
                required.put(c.id(), codes);
            }
        }
        List<UUID> out = new ArrayList<>(jdbc.sql("""
                        select id from ci where status <> 'retired' and owner_group_id is null
                         order by lower(name), id limit :limit""")
                .param("limit", limit).query(UUID.class).list());
        for (Map.Entry<UUID, List<String>> e : required.entrySet()) {
            if (out.size() >= limit) {
                break;
            }
            out.addAll(jdbc.sql("""
                            select id from ci where status <> 'retired' and owner_group_id is not null
                               and class_id = :cls and not jsonb_exists_all(attributes, cast(:keys as text[]))
                             order by lower(name), id limit :limit""")
                    .param("cls", e.getKey()).param("keys", e.getValue().toArray(String[]::new))
                    .param("limit", limit - out.size()).query(UUID.class).list());
        }
        return out;
    }

    private List<UUID> staleIds(int limit) {
        Instant now = Instant.now();
        return jdbc.sql("""
                        select c.id from ci c where c.status <> 'retired'
                           and (c.last_seen_at < :seen or c.last_seen_at is null and c.updated_at < :manual
                                or exists (select 1 from ci_source_record r where r.ci_id = c.id)
                                   and not exists (select 1 from ci_source_record r where r.ci_id = c.id
                                                      and r.removed_at is null and r.missing_since is null))
                         order by coalesce(c.last_seen_at, c.updated_at), c.id limit :limit""")
                .param("seen", Sql.ts(now.minus(Duration.ofDays(STALE_DAYS))))
                .param("manual", Sql.ts(now.minus(Duration.ofDays(MANUAL_STALE_DAYS))))
                .param("limit", limit).query(UUID.class).list();
    }

    private List<UUID> orphanIds(int limit) {
        return jdbc.sql("""
                        select c.id from ci c where c.status <> 'retired'
                           and not exists (select 1 from ci_relation r
                                            where r.valid_to is null and (r.source_id = c.id or r.target_id = c.id))
                         order by lower(c.name), c.id limit :limit""")
                .param("limit", limit).query(UUID.class).list();
    }

    private List<DuplicateGroup> duplicates(int limit) {
        ClassTree tree = classes.tree();
        Set<String> keys = new LinkedHashSet<>();
        for (ClassTree.ClassInfo c : tree.all()) {
            for (List<String> rule : tree.effectiveRules(c)) {
                if (rule.size() == 1) {
                    keys.add(rule.getFirst());
                }
            }
        }
        List<DuplicateGroup> out = new ArrayList<>();
        for (String key : keys) {
            jdbc.sql("""
                            select v, array_agg(id order by created_at) as ids
                              from (select attributes ->> :key as v, id, created_at from ci
                                     where status <> 'retired') s
                             where v is not null
                             group by v having count(*) > 1
                             order by v limit :limit""")
                    .param("key", key).param("limit", limit)
                    .query((rs, n) -> new Object[] {rs.getString("v"), rs.getArray("ids").getArray()})
                    .list()
                    .forEach(r -> out.add(group(key, (String) r[0], (Object[]) r[1])));
        }
        jdbc.sql("""
                        select min(name) as v, array_agg(id order by created_at) as ids from ci
                         where status <> 'retired' group by class_id, lower(name) having count(*) > 1
                         order by 1 limit :limit""")
                .param("limit", limit)
                .query((rs, n) -> new Object[] {rs.getString("v"), rs.getArray("ids").getArray()})
                .list()
                .forEach(r -> out.add(group("name", (String) r[0], (Object[]) r[1])));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private DuplicateGroup group(String key, String value, Object[] ids) {
        List<UUID> list = new ArrayList<>();
        for (Object o : ids) {
            list.add((UUID) o);
        }
        return new DuplicateGroup(key, value,
                store.findAll(list).stream().map(r -> CmdbController.CiRefView.of(r.ref())).toList());
    }
}
