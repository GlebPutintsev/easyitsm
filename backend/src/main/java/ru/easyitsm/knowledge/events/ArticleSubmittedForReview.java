package ru.easyitsm.knowledge.events;

import java.util.UUID;

/** Статья отправлена на проверку. {@code reviewerId == null} — проверить может любой редактор знаний. */
public record ArticleSubmittedForReview(UUID tenantId, UUID articleId, String key, String title, int versionNo,
                                        UUID reviewerId, UUID submittedBy) {
}
