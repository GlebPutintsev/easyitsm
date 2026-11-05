package ru.easyitsm.announcements.api;

import java.util.List;

/** Публичный интерфейс модуля для других модулей, например для страницы статуса. */
public interface AnnouncementQueries {

    /** Опубликованные объявления, которые действуют сейчас. */
    List<AnnouncementView> activeNow();
}
