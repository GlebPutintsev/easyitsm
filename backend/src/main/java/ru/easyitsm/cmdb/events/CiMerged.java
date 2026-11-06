package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;

/**
 * Дубликат {@code mergedId} слит в {@code ci} при сверке (CFG-05): связи, источники и история перенесены,
 * запись дубликата удалена. Модули, которые хранят ссылку на КЕ (заявки, активы, изменения), переводят её
 * с {@code mergedId} на {@code ci.id()}.
 */
public record CiMerged(UUID tenantId, Instant occurredAt, UUID actorId, String source, CiRef ci, UUID mergedId)
        implements CiEvent {
}
