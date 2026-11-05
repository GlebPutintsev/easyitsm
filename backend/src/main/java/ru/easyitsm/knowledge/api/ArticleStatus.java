package ru.easyitsm.knowledge.api;

/** Жизненный цикл статьи (5.7): Черновик → На проверке → Опубликована → На пересмотре → Архив. */
public enum ArticleStatus {
    DRAFT, REVIEW, PUBLISHED, REVIEW_DUE, ARCHIVED;

    public String code() {
        return name().toLowerCase();
    }

    public static ArticleStatus fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
