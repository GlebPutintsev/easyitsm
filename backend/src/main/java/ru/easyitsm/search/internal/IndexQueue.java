package ru.easyitsm.search.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Очередь индексации и состояние индекса арендатора. Все методы, кроме {@link #claim} и {@link #tenants},
 * работают в арендаторе потока (RLS).
 */
@Repository
class IndexQueue {

    /** Вид записи в индексе. */
    enum Kind {
        TICKET, ARTICLE;

        String code() {
            return name().toLowerCase();
        }

        static Kind fromCode(String code) {
            return valueOf(code.toUpperCase());
        }
    }

    record Claimed(UUID tenantId, Kind kind, UUID entityId, long seq, int attempts) {
    }

    record State(String indexName, Instant fullRequestedAt, Instant readyAt, Instant lastIndexedAt) {
    }

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcClient jdbc;

    IndexQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ставит записи в очередь. Уже стоящая запись получает новую версию и снова становится срочной. */
    void enqueue(Kind kind, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        insert into search_index_queue (tenant_id, kind, entity_id)
                        select :tenant, :kind, unnest(cast(:ids as uuid[]))
                        on conflict (tenant_id, kind, entity_id) do update
                           set seq = nextval('search_index_queue_seq'), enqueued_at = now(), next_attempt_at = now(),
                               leased_until = null, attempts = 0, last_error = null""")
                .param("tenant", TenantContext.require()).param("kind", kind.code())
                .param("ids", ids.stream().map(UUID::toString).distinct().collect(Collectors.joining(",", "{", "}")))
                .update();
    }

    /** Пачка по всем арендаторам под арендой (функция SECURITY DEFINER). */
    List<Claimed> claim(int limit, Duration lease) {
        return jdbc.sql("select tenant_id, kind, entity_id, seq, attempts from search_claim_batch(:limit, :lease)")
                .param("limit", limit).param("lease", (int) Math.max(1, lease.toSeconds()))
                .query((rs, n) -> new Claimed(rs.getObject("tenant_id", UUID.class), Kind.fromCode(rs.getString("kind")),
                        rs.getObject("entity_id", UUID.class), rs.getLong("seq"), rs.getInt("attempts")))
                .list();
    }

    /** Отправлено: строка удаляется, если с тех пор запись не поменялась ещё раз. */
    void done(Claimed c) {
        jdbc.sql("delete from search_index_queue where kind = :kind and entity_id = :id and seq = :seq")
                .param("kind", c.kind().code()).param("id", c.entityId()).param("seq", c.seq()).update();
    }

    /** Не удалось: повтор с растущей паузой до 5 минут, запись из очереди не пропадает. */
    void failed(Claimed c, String error) {
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), (long) Math.pow(2, Math.min(c.attempts(), 9)));
        jdbc.sql("""
                        update search_index_queue
                           set leased_until = null, next_attempt_at = now() + make_interval(secs => :delay),
                               last_error = left(:error, 500)
                         where kind = :kind and entity_id = :id and seq = :seq""")
                .param("delay", (int) seconds).param("error", error == null ? "" : error)
                .param("kind", c.kind().code()).param("id", c.entityId()).param("seq", c.seq()).update();
    }

    long pending() {
        return jdbc.sql("select count(*) from search_index_queue").query(Long.class).single();
    }

    Optional<String> lastError() {
        return jdbc.sql("select last_error from search_index_queue where last_error is not null"
                        + " order by next_attempt_at desc limit 1")
                .query(String.class).optional();
    }

    Optional<State> state() {
        return jdbc.sql("select index_name, full_requested_at, ready_at, last_indexed_at from search_index_state")
                .query((rs, n) -> new State(rs.getString("index_name"), instant(rs.getObject("full_requested_at",
                        OffsetDateTime.class)), instant(rs.getObject("ready_at", OffsetDateTime.class)),
                        instant(rs.getObject("last_indexed_at", OffsetDateTime.class))))
                .optional();
    }

    /** Запрошена полная загрузка. {@code resetReady} — индекс пуст или пересоздан, до конца загрузки ищем в БД. */
    void requestFull(String indexName, boolean resetReady) {
        jdbc.sql("""
                        insert into search_index_state (tenant_id, index_name, full_requested_at, ready_at)
                        values (:tenant, :index, now(), null)
                        on conflict (tenant_id) do update
                           set index_name = excluded.index_name, full_requested_at = now(), updated_at = now(),
                               ready_at = case when :reset then null else search_index_state.ready_at end""")
                .param("tenant", TenantContext.require()).param("index", indexName).param("reset", resetReady)
                .update();
    }

    /** Очередь арендатора пуста: индекс готов. */
    void markIndexed(boolean drained) {
        jdbc.sql("""
                        update search_index_state
                           set last_indexed_at = now(), updated_at = now(),
                               ready_at = case when :drained and ready_at is null then now() else ready_at end""")
                .param("drained", drained).update();
    }

    boolean drained() {
        return !jdbc.sql("select exists (select 1 from search_index_queue)").query(Boolean.class).single();
    }

    List<UUID> tenants() {
        return jdbc.sql("select tenant_id from search_tenants()").query(UUID.class).list();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
