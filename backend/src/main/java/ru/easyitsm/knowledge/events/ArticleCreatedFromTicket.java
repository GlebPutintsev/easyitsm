package ru.easyitsm.knowledge.events;

import java.util.UUID;

/**
 * Черновик статьи создан из решённой заявки (KB-05). Модуль tickets может добавить связь в ленту заявки.
 */
public record ArticleCreatedFromTicket(UUID tenantId, UUID articleId, String key, UUID ticketId, UUID createdBy) {
}
