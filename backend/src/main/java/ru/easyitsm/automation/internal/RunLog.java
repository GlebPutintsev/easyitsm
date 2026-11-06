package ru.easyitsm.automation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Таблица {@code automation_run}: журнал запусков и счётчики защиты от петель. */
@Component
class RunLog {

    private static final TypeReference<List<Map<String, Object>>> RESULTS = new TypeReference<>() {
    };

    /** Фильтр журнала: всё необязательно. */
    record Filter(UUID ruleId, String status, UUID ticketId) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RunLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Ограничивает ожидание блокировок в транзакции запуска: если другая транзакция того же потока держит строку
     * заявки, запуск завершится ошибкой {@code failed}, а не зависнет.
     */
    void limitLockWait() {
        jdbc.sql("set local lock_timeout = '10s'").update();
    }

    boolean exists(String runKey) {
        return jdbc.sql("select exists (select 1 from automation_run where run_key = :k)").param("k", runKey)
                .query(Boolean.class).single();
    }

    /** Сколько раз правило выполняло действия на заявке за последнее окно (пропуски не считаются). */
    int recentRuns(UUID ruleId, UUID ticketId, Duration window) {
        if (ruleId == null || ticketId == null) {
            return 0;
        }
        return jdbc.sql("""
                select count(*) from automation_run
                 where rule_id = :r and ticket_id = :t and status <> 'skipped' and started_at > :since""")
                .param("r", ruleId).param("t", ticketId)
                .param("since", Instant.now().minus(window).atOffset(ZoneOffset.UTC))
                .query(Integer.class).single();
    }

    /** Пишет запуск. Повтор того же ключа (повторная доставка события) ничего не делает. */
    void insert(Run run, String runKey) {
        var now = RuleStore.now();
        jdbc.sql("""
                insert into automation_run (id, tenant_id, rule_id, rule_name, rule_version, trigger, run_key, ticket_id,
                    ticket_number, depth, chain, status, skip_reason, results, error_code, error, started_at,
                    duration_ms, created_at, updated_at, version)
                values (:id, :tenant, :rule, :ruleName, :ruleVersion, :trigger, :key, :ticket, :number, :depth,
                    cast(:chain as uuid[]), :status, :skip, cast(:results as jsonb), :errorCode, :error, :started,
                    :duration, :now, :now, 0)
                on conflict (tenant_id, run_key) do nothing""")
                .param("id", run.id()).param("tenant", TenantContext.require())
                .param("rule", run.ruleId()).param("ruleName", run.ruleName()).param("ruleVersion", run.ruleVersion())
                .param("trigger", run.trigger()).param("key", runKey)
                .param("ticket", run.ticketId()).param("number", run.ticketNumber())
                .param("depth", run.depth())
                .param("chain", run.chain().stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")))
                .param("status", run.status()).param("skip", run.skipReason())
                .param("results", json.writeValueAsString(run.results()))
                .param("errorCode", run.errorCode()).param("error", run.error())
                .param("started", run.startedAt().atOffset(ZoneOffset.UTC)).param("duration", run.durationMs())
                .param("now", now)
                .update();
    }

    Optional<Run> find(UUID id) {
        return jdbc.sql("select * from automation_run where id = :id").param("id", id).query(this::map).optional();
    }

    /** Новые сверху, курсор по (started_at, id). */
    CursorPage<Run> page(Filter f, PageParams page) {
        KeysetCursor after = page.keyset();
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new java.util.HashMap<>();
        if (f.ruleId() != null) {
            where.add("rule_id = :rule");
            params.put("rule", f.ruleId());
        }
        if (f.status() != null) {
            where.add("status = :status");
            params.put("status", f.status());
        }
        if (f.ticketId() != null) {
            where.add("ticket_id = :ticket");
            params.put("ticket", f.ticketId());
        }
        if (after != null) {
            where.add("(started_at, id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid))");
            params.put("afterAt", Instant.parse(after.sortValue()).atOffset(ZoneOffset.UTC));
            params.put("afterId", after.id());
        }
        params.put("limit", page.limit() + 1);
        String query = "select * from automation_run" + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by started_at desc, id desc limit :limit";
        List<Run> rows = jdbc.sql(query).params(params).query(this::map).list();
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.startedAt().toString(), r.id()));
    }

    /** Удаляет журнал старше {@code keepDays} дней во всех арендаторах. */
    int purge(int keepDays) {
        Integer n = jdbc.sql("select automation_purge_runs(:d)").param("d", keepDays).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private Run map(ResultSet rs, int n) throws SQLException {
        java.sql.Array chain = rs.getArray("chain");
        List<UUID> ids = new ArrayList<>();
        if (chain != null) {
            for (Object v : (Object[]) chain.getArray()) {
                ids.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
            }
        }
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                rs.getString("rule_name"),
                rs.getLong("rule_version"),
                rs.getString("trigger"),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("ticket_number"),
                rs.getInt("depth"),
                List.copyOf(ids),
                rs.getString("status"),
                rs.getString("skip_reason"),
                json.readValue(rs.getString("results"), RESULTS),
                rs.getString("error_code"),
                rs.getString("error"),
                RuleStore.instant(rs, "started_at"),
                rs.getInt("duration_ms"));
    }

    static UUID newId() {
        return Ids.newId();
    }
}
