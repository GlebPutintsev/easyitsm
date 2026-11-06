package ru.easyitsm.assets.internal;

import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.cmdb.events.CiMerged;
import ru.easyitsm.cmdb.events.CiStatusChanged;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Следит за КЕ, на которые ссылаются активы (AST-05).
 * <ul>
 *   <li>{@link CiMerged} — дубликат слит при сверке: ссылка переводится на выжившую КЕ, в истории актива запись.</li>
 *   <li>{@link CiStatusChanged} в {@code RETIRED} — КЕ выведена из эксплуатации: запись в истории актива, сам актив
 *   не меняется (списание оборудования — отдельное решение).</li>
 * </ul>
 * Не {@code @ApplicationModuleListener}: арендатор ставится из события, затем новая транзакция (REQUIRES_NEW),
 * как в остальных слушателях чужих событий.
 */
@Component
class AssetCmdbListener {

    private final AssetRepository assets;
    private final AssetHistory history;
    private final TransactionTemplate tx;
    private final Clock clock;

    AssetCmdbListener(AssetRepository assets, AssetHistory history, PlatformTransactionManager tm,
                      Optional<Clock> clock) {
        this.assets = assets;
        this.history = history;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @TransactionalEventListener
    void on(CiMerged e) {
        TenantContext.runAs(e.tenantId(), () -> tx.executeWithoutResult(s -> {
            for (Asset a : assets.findByCi(e.mergedId())) {
                a.setCiId(e.ci().id());
                assets.save(a);
                history.record(a.getId(), new AssetHistory.Change("updated", null, null, null, null, null,
                        "КЕ объединена при сверке CMDB: теперь «" + e.ci().name() + "»"), clock.instant());
            }
        }));
    }

    @TransactionalEventListener
    void on(CiStatusChanged e) {
        if (e.ci().status() != CiStatus.RETIRED || e.previous() == CiStatus.RETIRED) {
            return;
        }
        TenantContext.runAs(e.tenantId(), () -> tx.executeWithoutResult(s -> {
            for (Asset a : assets.findByCi(e.ci().id())) {
                history.record(a.getId(), new AssetHistory.Change("updated", null, null, null, null, null,
                        "КЕ «" + e.ci().name() + "» выведена из эксплуатации"), clock.instant());
            }
        }));
    }
}
