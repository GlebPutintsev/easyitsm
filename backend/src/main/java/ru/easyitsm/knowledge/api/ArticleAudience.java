package ru.easyitsm.knowledge.api;

/** Кому видна опубликованная статья (KB-04). */
public enum ArticleAudience {
    /** Все пользователи, включая внешних заявителей. */
    ALL,
    /** Внутренние сотрудники. */
    EMPLOYEES,
    /** Агенты и другие сотрудники ИТ. */
    AGENTS,
    /** Участники одной группы. */
    GROUP;

    public String code() {
        return name().toLowerCase();
    }

    public static ArticleAudience fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
