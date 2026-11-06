package ru.easyitsm.audit.internal;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.audit.events.AuditIntegrityViolated;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Проверка цепочки хешей журнала арендатора (SEC-31, «Проверить целостность» в 10.16). Хеш каждой записи
 * пересчитывается той же функцией базы, что и при вставке, и сверяется с сохранённым; номер идёт без пропусков,
 * {@code prev_hash} равен хешу предыдущей записи, последняя запись совпадает с головой цепочки.
 * <p>
 * Ловит изменение записи, удаление из середины и обрезку хвоста. Полный пересчёт всей цепочки владельцем
 * базы локально не виден — для этого копия уходит в SIEM, где у администратора БД нет прав.
 */
@Component
class AuditChainVerifier {

    private static final int BATCH = 5000;
    static final String GENESIS = "0".repeat(64);

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    AuditChainVerifier(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Не readOnly: при нарушении публикуется событие, а outbox пишет его в базу. */
    @Transactional
    public AuditViews.Verification verify() {
        UUID tenant = TenantContext.require();
        Optional<Head> head = jdbc.sql("select seq, hash from audit_chain_head where tenant_id = :t")
                .param("t", tenant)
                .query((rs, n) -> new Head(rs.getLong("seq"), rs.getString("hash"))).optional();
        State s = new State();
        int fetched;
        do {
            fetched = batch(s);
        } while (fetched == BATCH && s.brokenAt == null);

        if (s.brokenAt == null && head.isPresent()
                && (head.get().seq() != s.lastSeq || !head.get().hash().equals(s.prev))) {
            s.broken(s.lastSeq + 1, "truncated");
        }
        if (s.brokenAt == null && head.isEmpty() && s.checked > 0) {
            s.broken(1, "truncated");
        }
        Instant now = Instant.now();
        var result = new AuditViews.Verification(s.brokenAt == null, s.checked, head.map(Head::seq).orElse(0L),
                head.map(Head::hash).orElse(GENESIS), s.brokenAt, s.problem, now);
        if (!result.valid()) {
            events.publishEvent(new AuditIntegrityViolated(tenant, s.brokenAt, s.problem, now));
        }
        return result;
    }

    private int batch(State s) {
        int[] n = {0};
        jdbc.sql("""
                select seq, prev_hash, hash, audit_entry_hash(a) as expected
                  from audit_log a where seq > :after order by seq limit :limit""")
                .param("after", s.lastSeq).param("limit", BATCH)
                .query((ResultSet rs) -> {
                    n[0]++;
                    if (s.brokenAt != null) {
                        return;
                    }
                    long seq = rs.getLong("seq");
                    String prevHash = rs.getString("prev_hash");
                    String hash = rs.getString("hash");
                    if (seq != s.lastSeq + 1) {
                        s.broken(s.lastSeq + 1, "gap");
                    } else if (!prevHash.equals(s.prev)) {
                        s.broken(seq, "link_mismatch");
                    } else if (!hash.equals(rs.getString("expected"))) {
                        s.broken(seq, "hash_mismatch");
                    }
                    s.checked++;
                    s.lastSeq = seq;
                    s.prev = hash;
                });
        return n[0];
    }

    private record Head(long seq, String hash) {
    }

    private static final class State {
        long lastSeq;
        String prev = GENESIS;
        long checked;
        Long brokenAt;
        String problem;

        void broken(long seq, String problem) {
            this.brokenAt = seq;
            this.problem = problem;
        }
    }
}
