package ru.easyitsm.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.admin.api.ConfigHistory;
import ru.easyitsm.admin.events.ConfigChanged;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * Версии настроек и журнал действий администратора (ADM-06). Каждая запись — новая версия с полным снимком,
 * строки не меняются (UPDATE и DELETE отозваны у роли приложения).
 */
@Service
class ConfigVersionService implements ConfigHistory {

    static final Pattern ENTITY = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_.]*$");
    static final Set<String> ACTIONS = Set.of("create", "update", "delete", "restore", "import", "rollback", "setup");
    /** Снимок больше этого размера — ошибка: настройка такого размера означает, что в версию попали данные. */
    static final int MAX_SNAPSHOT = 512 * 1024;

    private final JdbcClient jdbc;
    private final AdminJson json;
    private final ApplicationEventPublisher events;

    ConfigVersionService(JdbcClient jdbc, AdminJson json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
    }

    record Summary(UUID id, String entity, String entityId, String label, int versionNo, String action,
                   String comment, String source, UUID authorId, Instant createdAt) {
    }

    record Version(UUID id, String entity, String entityId, String label, int versionNo, String action,
                   String comment, String source, UUID authorId, Instant createdAt, Object snapshot) {
    }

    record Diff(Summary from, Summary to, List<JsonDiff.Change> changes) {
    }

    @Override
    @Transactional
    public UUID record(String entity, String entityId, String label, String action, Object snapshot, String comment) {
        return save(entity, entityId, label, action, snapshot, comment, "server").id();
    }

    @Transactional
    Summary save(String entity, String entityId, String label, String action, Object snapshot, String comment,
                 String source) {
        if (entity == null || !ENTITY.matcher(entity).matches() || entity.length() > 100) {
            throw ApiException.badRequest("invalid_entity", "Вид настройки задаётся как <модуль>.<объект>");
        }
        if (!ACTIONS.contains(action)) {
            throw ApiException.badRequest("invalid_action", "Неизвестное действие: " + action);
        }
        String id = entityId == null ? "" : entityId.trim();
        if (id.length() > 100) {
            throw ApiException.badRequest("invalid_entity_id", "Слишком длинный идентификатор");
        }
        String doc = json.write(snapshot == null ? Map.of() : snapshot);
        if (doc.length() > MAX_SNAPSHOT) {
            throw ApiException.badRequest("snapshot_too_large", "Снимок настройки больше 512 КБ");
        }
        String safeLabel = label == null ? "" : truncate(label.trim(), 255);
        String safeComment = comment == null ? "" : truncate(comment.trim(), 1000);
        UUID actor = CurrentActor.userId().orElse(null);
        UUID tenant = TenantContext.require();
        Instant now = Instant.now();
        UUID versionId = Ids.newId();
        int no = insert(versionId, tenant, entity, id, safeLabel, action, doc, safeComment, source, actor, now);
        events.publishEvent(new ConfigChanged(tenant, versionId, entity, id, no, action, actor, now));
        return new Summary(versionId, entity, id, safeLabel, no, action, safeComment, source, actor, now);
    }

    /** Номер версии — следующий по (entity, entity_id). Одновременная запись упирается в уникальный ключ. */
    private int insert(UUID versionId, UUID tenant, String entity, String entityId, String label, String action,
                       String doc, String comment, String source, UUID actor, Instant now) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtext(:k))").param("k", tenant + "|" + entity + "|" + entityId)
                .query(Integer.class).single();
        int next = jdbc.sql("""
                select coalesce(max(version_no), 0) + 1 from config_version
                 where entity = :entity and entity_id = :entityId""")
                .param("entity", entity).param("entityId", entityId).query(Integer.class).single();
        try {
            jdbc.sql("""
                    insert into config_version (id, tenant_id, entity, entity_id, label, version_no, action, snapshot,
                                                comment, source, created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :entity, :entityId, :label, :no, :action, cast(:snapshot as jsonb),
                            :comment, :source, :now, :actor, :now, :actor, 0)""")
                    .param("id", versionId).param("tenant", tenant).param("entity", entity)
                    .param("entityId", entityId).param("label", label).param("no", next).param("action", action)
                    .param("snapshot", doc).param("comment", comment).param("source", source)
                    .param("now", AdminJson.ts(now)).param("actor", actor)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("version_conflict", "Настройку одновременно сохранили дважды. Повторите");
        }
        return next;
    }

    @Transactional(readOnly = true)
    CursorPage<Summary> list(String entity, String entityId, UUID author, String action, Instant from, Instant to,
                             PageParams page) {
        KeysetCursor after = page.keyset();
        List<Summary> rows = jdbc.sql("""
                select id, entity, entity_id, label, version_no, action, comment, source, created_by, created_at
                  from config_version
                 where (cast(:entity as text) is null or entity = :entity or entity like :entityPrefix)
                   and (cast(:entityId as text) is null or entity_id = :entityId)
                   and (cast(:author as uuid) is null or created_by = :author)
                   and (cast(:action as text) is null or action = :action)
                   and (cast(:from as timestamptz) is null or created_at >= :from)
                   and (cast(:to as timestamptz) is null or created_at < :to)
                   and (cast(:afterAt as timestamptz) is null
                        or (created_at, id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid)))
                 order by created_at desc, id desc
                 limit :limit""")
                .param("entity", blankToNull(entity))
                .param("entityPrefix", blankToNull(entity) == null ? null : entity.trim() + ".%")
                .param("entityId", entityId)
                .param("author", author).param("action", blankToNull(action))
                .param("from", AdminJson.ts(from)).param("to", AdminJson.ts(to))
                .param("afterAt", after == null ? null : AdminJson.ts(Instant.parse(after.sortValue())))
                .param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> summary(rs))
                .list();
        return CursorPage.of(rows, page.limit(), s -> new KeysetCursor(s.createdAt().toString(), s.id()));
    }

    @Transactional(readOnly = true)
    Version get(UUID id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Версия настройки"));
    }

    /** Последняя версия объекта, если есть. */
    @Transactional(readOnly = true)
    Optional<Version> latest(String entity, String entityId) {
        return jdbc.sql("""
                select *, snapshot::text as snapshot_json from config_version
                 where entity = :entity and entity_id = :entityId order by version_no desc limit 1""")
                .param("entity", entity).param("entityId", entityId == null ? "" : entityId)
                .query((rs, n) -> version(rs)).optional();
    }

    /** Сравнение с версией {@code againstId}, по умолчанию с предыдущей версией того же объекта. */
    @Transactional(readOnly = true)
    Diff diff(UUID id, UUID againstId) {
        Version to = get(id);
        Version from;
        if (againstId != null) {
            from = get(againstId);
            if (!from.entity().equals(to.entity()) || !from.entityId().equals(to.entityId())) {
                throw ApiException.badRequest("different_entity", "Сравнивать можно версии одной настройки");
            }
        } else {
            from = jdbc.sql("""
                    select *, snapshot::text as snapshot_json from config_version
                     where entity = :entity and entity_id = :entityId and version_no < :no
                     order by version_no desc limit 1""")
                    .param("entity", to.entity()).param("entityId", to.entityId()).param("no", to.versionNo())
                    .query((rs, n) -> version(rs)).optional().orElse(null);
        }
        return new Diff(from == null ? null : summaryOf(from), summaryOf(to),
                JsonDiff.diff(from == null ? null : from.snapshot(), to.snapshot()));
    }

    private Optional<Version> find(UUID id) {
        return jdbc.sql("select *, snapshot::text as snapshot_json from config_version where id = :id")
                .param("id", id).query((rs, n) -> version(rs)).optional();
    }

    private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Summary(AdminJson.uuid(rs, "id"), rs.getString("entity"), rs.getString("entity_id"),
                rs.getString("label"), rs.getInt("version_no"), rs.getString("action"), rs.getString("comment"),
                rs.getString("source"), AdminJson.uuid(rs, "created_by"), AdminJson.instant(rs, "created_at"));
    }

    private Version version(java.sql.ResultSet rs) throws java.sql.SQLException {
        Summary s = summary(rs);
        return new Version(s.id(), s.entity(), s.entityId(), s.label(), s.versionNo(), s.action(), s.comment(),
                s.source(), s.authorId(), s.createdAt(), json.tree(rs.getString("snapshot_json")));
    }

    private static Summary summaryOf(Version v) {
        return new Summary(v.id(), v.entity(), v.entityId(), v.label(), v.versionNo(), v.action(), v.comment(),
                v.source(), v.authorId(), v.createdAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
