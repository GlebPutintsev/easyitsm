package ru.easyitsm.announcements.events;

import java.util.UUID;

/** Объявление опубликовано. Слушатели: уведомления, страница статуса. */
public record AnnouncementPublished(UUID tenantId, UUID announcementId, String kind, String title) {
}
