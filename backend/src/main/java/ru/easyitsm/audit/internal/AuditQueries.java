package ru.easyitsm.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Чтение журнала под RLS арендатора. Все условия — параметры запроса (SEC-22). */
@Component
class AuditQueries {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> CATEGORIES = Set.of("auth", "access", "config", "data", "security", "audit");
    private static final Set<String> RESULTS = Set.of("success", "failure", "denied");
    static final String COLUMNS = """
            id, seq, at, category, action, result, actor_id, actor_login, object_type, object_id, ip, user_agent,
            trace_id, details::text as details, prev_hash, hash""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditQueries(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    CursorPage<AuditViews.Event> list(AuditViews.Filter filter, PageParams page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = where(filter, params);
        KeysetCursor cursor = page.keyset();
        if (cursor != null) {
            try {
                params.put("afterSeq", Long.parseLong(cursor.sortValue()));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("invalid_cursor", "Некорректный курсор");
            }
            where.append(" and seq < :afterSeq");
        }
        params.put("limit", page.limit() + 1);
        List<AuditViews.Event> rows = jdbc.sql("select " + COLUMNS + " from audit_log where " + where
                        + " order by seq desc limit :limit")
                .params(params).query(this::map).list();
        return CursorPage.of(rows, page.limit(), e -> new KeysetCursor(Long.toString(e.seq()), e.id()));
    }

    AuditViews.Event get(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from audit_log where id = :id").param("id", id)
                .query(this::map).optional()
                .orElseThrow(() -> ApiException.notFound("Запись журнала"));
    }

    /** Выгрузка по фильтру, от новых к старым; не больше {@code max} строк. Возвращает число строк. */
    long stream(AuditViews.Filter filter, int max, Consumer<AuditViews.Event> sink) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = where(filter, params);
        params.put("limit", max);
        long[] n = {0};
        jdbc.sql("select " + COLUMNS + " from audit_log where " + where + " order by seq desc limit :limit")
                .params(params)
                .query((ResultSet rs) -> {
                    sink.accept(map(rs, 0));
                    n[0]++;
                });
        return n[0];
    }

    private static StringBuilder where(AuditViews.Filter f, Map<String, Object> params) {
        StringBuilder w = new StringBuilder("true");
        if (f.from() != null) {
            w.append(" and at >= :from");
            params.put("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            w.append(" and at < :to");
            params.put("to", Timestamp.from(f.to()));
        }
        if (f.category() != null) {
            if (!CATEGORIES.contains(f.category())) {
                throw ApiException.badRequest("invalid_category", "Неизвестный раздел журнала");
            }
            w.append(" and category = :category");
            params.put("category", f.category());
        }
        if (f.result() != null) {
            if (!RESULTS.contains(f.result())) {
                throw ApiException.badRequest("invalid_result", "Неизвестный результат");
            }
            w.append(" and result = :result");
            params.put("result", f.result());
        }
        if (f.action() != null && !f.action().isBlank()) {
            if (f.action().endsWith(".")) {
                w.append(" and starts_with(action, :action)");
            } else {
                w.append(" and action = :action");
            }
            params.put("action", f.action());
        }
        if (f.actorId() != null) {
            w.append(" and actor_id = :actor");
            params.put("actor", f.actorId());
        }
        if (f.objectType() != null && !f.objectType().isBlank()) {
            w.append(" and object_type = :objectType");
            params.put("objectType", f.objectType());
        }
        if (f.objectId() != null && !f.objectId().isBlank()) {
            w.append(" and object_id = :objectId");
            params.put("objectId", f.objectId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            w.append(" and (action ilike :q or object_type ilike :q or object_id ilike :q or actor_login ilike :q"
                    + " or ip ilike :q or trace_id ilike :q or details::text ilike :q)");
            params.put("q", "%" + likeEscape(f.q().strip()) + "%");
        }
        return w;
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    AuditViews.Event map(ResultSet rs, int n) throws SQLException {
        return new AuditViews.Event(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getTimestamp("at").toInstant(),
                rs.getString("category"),
                rs.getString("action"),
                rs.getString("result"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_login"),
                rs.getString("object_type"),
                rs.getString("object_id"),
                rs.getString("ip"),
                rs.getString("user_agent"),
                rs.getString("trace_id"),
                json.readValue(rs.getString("details"), MAP),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }

    /** Записи после {@code afterSeq} по возрастанию номера: для SIEM. */
    List<AuditViews.Event> after(long afterSeq, int limit) {
        List<AuditViews.Event> out = new ArrayList<>();
        jdbc.sql("select " + COLUMNS + " from audit_log where seq > :after order by seq limit :limit")
                .param("after", afterSeq).param("limit", limit)
                .query((ResultSet rs) -> {
                    out.add(map(rs, 0));
                });
        return out;
    }

    long headSeq() {
        return jdbc.sql("select seq from audit_chain_head where tenant_id = app_current_tenant()")
                .query(Long.class).optional().orElse(0L);
    }

    java.util.Optional<Map<String, Object>> siemState(UUID tenant) {
        return jdbc.sql("""
                        select delivered_seq, delivered_at, failures, next_attempt_at, last_error, last_error_at
                          from audit_siem_state where tenant_id = :t""")
                .param("t", tenant)
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("deliveredSeq", rs.getLong("delivered_seq"));
                    m.put("deliveredAt", instant(rs.getTimestamp("delivered_at")));
                    m.put("failures", rs.getInt("failures"));
                    m.put("nextAttemptAt", instant(rs.getTimestamp("next_attempt_at")));
                    m.put("lastError", rs.getString("last_error"));
                    m.put("lastErrorAt", instant(rs.getTimestamp("last_error_at")));
                    return m;
                }).optional();
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
