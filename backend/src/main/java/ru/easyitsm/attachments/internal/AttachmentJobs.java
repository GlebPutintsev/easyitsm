package ru.easyitsm.attachments.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/** Плановые задачи вложений в роли worker: проверка карантина и очистка брошенных загрузок. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class AttachmentJobs {

    private static final Logger log = LoggerFactory.getLogger(AttachmentJobs.class);
    private static final int BATCH = 10;

    private final AttachmentStore store;
    private final FileScanner scanner;
    private final ObjectStorage storage;

    AttachmentJobs(AttachmentStore store, FileScanner scanner, ObjectStorage storage) {
        this.store = store;
        this.scanner = scanner;
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    void init() {
        try {
            storage.ensureBuckets();
        } catch (RuntimeException e) {
            log.warn("Хранилище файлов недоступно при старте: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${easyitsm.attachments.scan-interval:PT5S}", initialDelayString = "PT15S")
    void scan() {
        runScans();
    }

    /** @return сколько файлов взято на проверку */
    int runScans() {
        var due = store.claimScans(BATCH, 600);
        for (var d : due) {
            try {
                TenantContext.runAs(d.tenantId(), () -> scanner.scan(d.id()));
            } catch (RuntimeException e) {
                log.warn("Проверка файла {}: {}", d.id(), e.getMessage());
            }
        }
        return due.size();
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
    void cleanup() {
        for (var d : store.expiredUploads(500)) {
            try {
                TenantContext.runAs(d.tenantId(), () -> store.findAny(d.id()).ifPresent(a -> {
                    storage.deleteQuarantine(a.storageKey());
                    store.purge(a.id());
                }));
            } catch (RuntimeException e) {
                log.warn("Очистка незавершённой загрузки {}: {}", d.id(), e.getMessage());
            }
        }
    }
}
