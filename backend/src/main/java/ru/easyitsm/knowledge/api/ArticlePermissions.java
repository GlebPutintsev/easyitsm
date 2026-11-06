package ru.easyitsm.knowledge.api;

import java.util.UUID;

/**
 * Права текущего пользователя на статью для модулей, которые прикрепляют к ней данные (картинки и файлы).
 *
 * @param canRead читает опубликованную версию или рабочую копию
 * @param canEdit правит рабочую копию
 */
public record ArticlePermissions(UUID articleId, String key, boolean canRead, boolean canEdit) {
}
