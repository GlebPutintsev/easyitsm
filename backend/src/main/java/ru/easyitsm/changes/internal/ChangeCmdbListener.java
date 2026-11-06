package ru.easyitsm.changes.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.cmdb.events.CiMerged;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Дубликат КЕ слит при сверке CMDB ({@link CiMerged}): изменения, шаблоны и заморозки ссылаются на выжившую КЕ,
 * иначе конфликты в календаре и анализ влияния её не увидят.
 * <p>
 * Не {@code @ApplicationModuleListener}: арендатор ставится из события, транзакция — новая (REQUIRES_NEW).
 */
@Component
class ChangeCmdbListener {

    private final ChangeStore store;
    private final TransactionTemplate tx;

    ChangeCmdbListener(ChangeStore store, PlatformTransactionManager tm) {
        this.store = store;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    void on(CiMerged e) {
        TenantContext.runAs(e.tenantId(), () -> tx.executeWithoutResult(s -> store.replaceCi(e.mergedId(), e.ci().id())));
    }
}
