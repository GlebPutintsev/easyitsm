package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;

/**
 * Дубликат {@code mergedId} слит в {@code ci} при сверке или вручную (CFG-05, CFG-07): связи и объекты источников
 * перенесены. Дубликат остаётся выведенным из эксплуатации ({@code retired}) со ссылкой {@code mergedInto} и своей
 * историей, поэтому старые ссылки на него не ломаются. Модули, которые хранят ссылку на КЕ (заявки, активы,
 * изменения), переводят её с {@code mergedId} на {@code ci.id()}.
 */
public record CiMerged(UUID tenantId, Instant occurredAt, UUID actorId, String source, CiRef ci, UUID mergedId)
        implements CiEvent {
}
