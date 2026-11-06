package ru.easyitsm.admin.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Записана новая версия настройки. Модуль аудита кладёт её в защищённый журнал (ADM-06, SEC-13);
 * снимок не передаётся, его можно прочитать по {@code versionId}.
 */
public record ConfigChanged(UUID tenantId, UUID versionId, String entity, String entityId, int versionNo,
                            String action, UUID actorId, Instant at) {
}
