package ru.easyitsm.audit.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Ночная проверка целостности журнала всех арендаторов. Результат пишется в журнал ({@code audit.verify})
 * и, значит, уходит в SIEM; нарушение публикует {@code AuditIntegrityViolated}.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class AuditIntegrityJob {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityJob.class);

    private final JdbcClient jdbc;
    private final AuditChainVerifier verifier;
    private final AuditWriter writer;

    AuditIntegrityJob(JdbcClient jdbc, AuditChainVerifier verifier, AuditWriter writer) {
        this.jdbc = jdbc;
        this.verifier = verifier;
        this.writer = writer;
    }

    @Scheduled(cron = "${easyitsm.audit.verify-cron:0 15 3 * * *}")
    void verifyAll() {
        for (UUID tenant : jdbc.sql("select tenant_id from audit_tenants()").query(UUID.class).list()) {
            try {
                TenantContext.runAs(tenant, () -> {
                    var result = verifier.verify();
                    writer.recordSeparately(AuditController.verificationEntry(result).detail("trigger", "schedule"));
                    if (!result.valid()) {
                        log.error("Нарушена цепочка журнала аудита арендатора {}: {} на записи {}", tenant,
                                result.problem(), result.brokenAtSeq());
                    }
                });
            } catch (RuntimeException e) {
                log.error("Не удалось проверить журнал аудита арендатора {}", tenant, e);
            }
        }
    }
}
