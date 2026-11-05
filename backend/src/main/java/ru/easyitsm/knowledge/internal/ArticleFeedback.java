package ru.easyitsm.knowledge.internal;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Оценка читателя «Помогло / Не помогло» (10.12). Одна на пользователя, повторная оценка заменяет прежнюю. */
@Table("article_feedback")
class ArticleFeedback extends TenantEntity {

    private UUID articleId;
    private UUID userId;
    private boolean helpful;
    private String comment;

    protected ArticleFeedback() {
    }

    ArticleFeedback(UUID articleId, UUID userId) {
        this.articleId = articleId;
        this.userId = userId;
        this.comment = "";
    }

    void rate(boolean helpful, String comment) {
        this.helpful = helpful;
        this.comment = comment == null ? "" : comment;
    }

    boolean helpful() {
        return helpful;
    }
}
