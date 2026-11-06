package ru.easyitsm.mim.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Опубликовано сообщение о крупном инциденте (MIM-04). Для каналов вроде мессенджеров: текст уже готов.
 *
 * @param audience   {@code users} или {@code management}
 * @param statusPage показано на странице статуса
 */
public record MajorIncidentUpdatePublished(UUID tenantId, Instant occurredAt, UUID mimId, UUID updateId,
                                           String audience, String title, String body, boolean statusPage,
                                           Instant nextUpdateAt) {
}
