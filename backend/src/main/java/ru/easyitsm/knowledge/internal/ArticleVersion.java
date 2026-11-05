package ru.easyitsm.knowledge.internal;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/** Версия статьи (KB-03): заголовок и текст в Markdown. Меняется только пока это рабочий черновик. */
@Table("article_version")
class ArticleVersion extends TenantEntity {

    private UUID articleId;
    private int versionNo;
    private String title;
    private String body;
    private String notes;
    private Integer restoredFrom;

    protected ArticleVersion() {
    }

    ArticleVersion(UUID articleId, int versionNo, String title, String body) {
        this.articleId = articleId;
        this.versionNo = versionNo;
        this.title = title;
        this.body = body == null ? "" : body;
        this.notes = "";
    }

    void setTitle(String title) {
        this.title = title;
    }

    void setBody(String body) {
        this.body = body;
    }

    void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    void setRestoredFrom(Integer restoredFrom) {
        this.restoredFrom = restoredFrom;
    }

    UUID articleId() {
        return articleId;
    }

    int versionNo() {
        return versionNo;
    }

    String title() {
        return title;
    }

    String body() {
        return body;
    }

    String notes() {
        return notes;
    }

    Integer restoredFrom() {
        return restoredFrom;
    }

    /** Автор версии в смысле проверки четырёх глаз: создавший или последним менявший её. */
    boolean authoredBy(UUID userId) {
        return userId != null && (userId.equals(getCreatedBy()) || userId.equals(getUpdatedBy()));
    }
}
