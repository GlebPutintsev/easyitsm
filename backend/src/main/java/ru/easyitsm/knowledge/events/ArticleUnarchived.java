package ru.easyitsm.knowledge.events;

import java.util.UUID;

/** Статья возвращена из архива: опубликованная версия снова видна читателям и поиску. */
public record ArticleUnarchived(UUID tenantId, UUID articleId, String key, UUID restoredBy) {
}
