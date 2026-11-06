package ru.easyitsm.mim.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Дежурный оповещён о крупном инциденте (MIM-03). Центр уведомлений и почту модуль отправляет сам; другие каналы
 * (мессенджеры, SMS, звонок) слушают это событие. Подтверждение — {@code POST /major-incidents/{id}/pages/{pageId}/ack}.
 *
 * @param step  шаг цепочки, с 0
 * @param channel {@code center} или {@code email}
 */
public record OnCallPaged(UUID tenantId, Instant occurredAt, UUID mimId, UUID pageId, UUID userId, int step,
                          String channel, String title, String link) {
}
