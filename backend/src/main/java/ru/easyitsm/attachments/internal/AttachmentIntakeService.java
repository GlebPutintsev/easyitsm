package ru.easyitsm.attachments.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.attachments.api.AttachmentIntake;
import ru.easyitsm.attachments.api.IncomingFile;
import ru.easyitsm.attachments.events.AttachmentQuarantined;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;

/**
 * Приём файлов от почты и мессенджеров: сразу в карантин и на ту же проверку, что и загрузка из браузера.
 * Файл сверх пределов не хранится, остаётся отметка {@code blocked}: агент видит, что в письме что-то было.
 */
@Service
class AttachmentIntakeService implements AttachmentIntake {

    private static final Logger log = LoggerFactory.getLogger(AttachmentIntakeService.class);
    private static final Set<String> OWNERS = Set.of(OwnerAccess.TICKET, OwnerAccess.ARTICLE);

    private final AttachmentStore store;
    private final ObjectStorage storage;
    private final AttachmentProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AttachmentIntakeService(AttachmentStore store, ObjectStorage storage, AttachmentProperties props,
                            ApplicationEventPublisher events, Optional<Clock> clock) {
        this.store = store;
        this.storage = storage;
        this.props = props;
        this.events = events;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * Сначала объект в S3, потом запись: при недоступном хранилище в БД ничего не меняется, и транзакция
     * вызывающего (письмо уже создало заявку) не помечается к откату.
     */
    @Override
    @Transactional(noRollbackFor = AttachmentIntake.StorageUnavailable.class)
    public Accepted accept(IncomingFile file) {
        String ownerType = file.ownerType().strip().toLowerCase(Locale.ROOT);
        if (!OWNERS.contains(ownerType)) {
            throw new IllegalArgumentException("ownerType: ticket или article, получено " + file.ownerType());
        }
        String visibility = file.visibility().strip().toLowerCase(Locale.ROOT);
        if (!visibility.equals("public") && !visibility.equals("internal")) {
            throw new IllegalArgumentException("visibility: public или internal, получено " + file.visibility());
        }
        String name = name(file.fileName());
        String declared = AttachmentService.safeMime(file.contentType());
        UUID id = Ids.newId();
        UUID tenant = TenantContext.require();
        String key = "quarantine/" + tenant + "/" + id;
        Instant now = clock.instant();
        AttachmentRow row = new AttachmentRow(id, tenant, ownerType, file.ownerId(), visibility, name, declared, null,
                file.content().length, null, key, AttachmentRow.AWAITING, null, null, null, 0,
                now.plus(props.uploadUrlTtl()), null, now, file.uploadedBy(), now, 0);

        String reason = null;
        if (file.content().length > props.maxFileSize().toBytes()) {
            reason = "file_too_large";
        } else if (store.countByOwner(ownerType, file.ownerId()) >= props.maxPerOwner()) {
            reason = "too_many_attachments";
        }
        if (reason != null) {
            store.insert(row);
            store.finishScan(id, AttachmentRow.BLOCKED, declared, null, "policy", reason, now);
            events.publishEvent(new AttachmentQuarantined(tenant, id, ownerType, file.ownerId(), name,
                    AttachmentRow.BLOCKED, reason, file.uploadedBy()));
            log.warn("Файл {} из {} не принят: {}", name, file.source(), reason);
            return new Accepted(id, AttachmentRow.BLOCKED, reason);
        }
        try {
            storage.putQuarantine(key, file.content(), declared);
        } catch (ObjectStorage.StorageUnavailable e) {
            throw new AttachmentIntake.StorageUnavailable(e.getMessage(), e);
        }
        store.insert(row);
        store.markScanning(id, file.uploadedBy(), now);
        return new Accepted(id, AttachmentRow.SCANNING, null);
    }

    private static String name(String raw) {
        try {
            return AttachmentService.cleanName(raw == null ? "" : raw);
        } catch (ApiException e) {
            return "attachment";
        }
    }
}
