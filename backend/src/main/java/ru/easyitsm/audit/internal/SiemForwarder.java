package ru.easyitsm.audit.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import ru.easyitsm.audit.api.Masking;

/**
 * Доставка журнала в SIEM почти в реальном времени (SEC-32). Для каждого арендатора помнит номер последней
 * доставленной записи; строка состояния берётся {@code FOR UPDATE SKIP LOCKED}, поэтому несколько подов worker
 * не отправят одну запись дважды. При сбое — повтор с паузой 5 с, 10 с, 20 с… до {@code max-backoff}.
 * <p>
 * Сам запуск по расписанию — {@link SiemJob} (роль worker); здесь доставка для одного арендатора и проверка связи.
 */
@Component
class SiemForwarder {

    private static final Logger log = LoggerFactory.getLogger(SiemForwarder.class);
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final AuditQueries queries;
    private final AuditProperties.Siem props;
    private final SiemFormatter formatter;
    private final SiemTransport transport;
    private final AtomicLong lag = new AtomicLong();

    SiemForwarder(JdbcClient jdbc, AuditQueries queries, AuditProperties props, ObjectMapper json,
                  ObjectProvider<MeterRegistry> meters) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.props = props.siem();
        this.formatter = new SiemFormatter(this.props.format(), this.props.appName(), hostname(), version(), json);
        this.transport = new SiemTransport(this.props);
        meters.ifAvailable(r -> Gauge.builder("easyitsm.audit.siem.lag", lag, AtomicLong::get)
                .description("Записи журнала аудита, ещё не доставленные в SIEM (последний проход)")
                .register(r));
    }

    AuditProperties.Siem settings() {
        return props;
    }

    /** Арендаторы, у которых есть что отправить. */
    List<UUID> pendingTenants(int max) {
        return jdbc.sql("select tenant_id from audit_siem_pending(:max)").param("max", max)
                .query(UUID.class).list();
    }

    void resetLag() {
        lag.set(0);
    }

    /**
     * Отправляет одну пачку записей текущего арендатора. Возвращает число отправленных записей; 0 — нечего
     * отправлять, другой под уже отправляет или ошибка (она сохранена в состоянии).
     */
    @Transactional
    public int deliverBatch() {
        UUID tenant = ru.easyitsm.platform.tenant.TenantContext.require();
        jdbc.sql("insert into audit_siem_state (tenant_id) values (:t) on conflict (tenant_id) do nothing")
                .param("t", tenant).update();
        Optional<Long> delivered = jdbc.sql("""
                        select delivered_seq from audit_siem_state where tenant_id = :t
                           and (next_attempt_at is null or next_attempt_at <= now())
                           for update skip locked""")
                .param("t", tenant).query(Long.class).optional();
        if (delivered.isEmpty()) {
            return 0;
        }
        List<AuditViews.Event> events = queries.after(delivered.get(), props.batchSize());
        if (events.isEmpty()) {
            return 0;
        }
        String code = tenantCode(tenant);
        try {
            transport.send(events.stream().map(e -> formatter.frame(e, code)).toList());
        } catch (Exception e) {
            failed(tenant, e);
            return 0;
        }
        long last = events.getLast().seq();
        jdbc.sql("""
                update audit_siem_state set delivered_seq = :seq, delivered_at = now(), failures = 0,
                       next_attempt_at = null, last_error = null
                 where tenant_id = :t""")
                .param("seq", last).param("t", tenant).update();
        long head = jdbc.sql("select seq from audit_chain_head where tenant_id = :t").param("t", tenant)
                .query(Long.class).optional().orElse(last);
        lag.addAndGet(Math.max(0, head - last));
        return events.size();
    }

    private void failed(UUID tenant, Exception e) {
        int failures = jdbc.sql("select failures from audit_siem_state where tenant_id = :t").param("t", tenant)
                .query(Integer.class).single() + 1;
        Duration pause = backoff(failures, props.maxBackoff());
        String error = Masking.text(e.getClass().getSimpleName() + ": " + e.getMessage());
        jdbc.sql("""
                update audit_siem_state set failures = :f, next_attempt_at = :next, last_error = :err,
                       last_error_at = now()
                 where tenant_id = :t""")
                .param("f", failures).param("next", Timestamp.from(Instant.now().plus(pause)))
                .param("err", error.length() > 500 ? error.substring(0, 500) : error).param("t", tenant).update();
        log.warn("SIEM недоступен ({}), повтор через {} с, попытка {}", error, pause.toSeconds(), failures);
    }

    static Duration backoff(int failures, Duration max) {
        long seconds = FIRST_RETRY.toSeconds() << Math.min(failures - 1, 20);
        return seconds >= max.toSeconds() ? max : Duration.ofSeconds(seconds);
    }

    /** «Проверить» на экране журнала: одно тестовое сообщение прямо сейчас. */
    void sendTest(AuditViews.Event probe, String tenantCode) throws Exception {
        transport.send(List.of(formatter.frame(probe, tenantCode)));
    }

    String tenantCode(UUID tenant) {
        return jdbc.sql("select code from tenant where id = :t").param("t", tenant).query(String.class)
                .optional().orElse(tenant.toString());
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "easyitsm";
        }
    }

    private static String version() {
        String v = SiemForwarder.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
