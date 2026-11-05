package ru.easyitsm.knowledge.internal;

import ru.easyitsm.knowledge.api.ArticleStatus;

/**
 * Правила доступа к статье (ACC-01, ACC-02, KB-04). Та же логика видимости повторена в SQL
 * {@link ArticleSearch}: меняя одно, меняйте и другое — это проверяют тесты KnowledgeAccessTest.
 */
final class ArticleAccess {

    private ArticleAccess() {
    }

    /** Опубликованная версия видна по аудитории. */
    static boolean canReadPublished(Reader r, Article a) {
        if (!a.isReadable()) {
            return false;
        }
        if (r.seesEverything()) {
            return true;
        }
        return switch (a.audience()) {
            case ALL -> true;
            case EMPLOYEES -> r.employee();
            case AGENTS -> r.staff();
            case GROUP -> r.groups().contains(a.audienceGroupId());
        };
    }

    /** Видна рабочая копия, история версий и служебные поля. */
    static boolean canReadWorkspace(Reader r, Article a) {
        return r.seesEverything()
                || r.userId().equals(a.ownerId())
                || r.userId().equals(a.getCreatedBy())
                || (r.editor() && canReadPublished(r, a));
    }

    static boolean canRead(Reader r, Article a) {
        return canReadWorkspace(r, a) || canReadPublished(r, a);
    }

    static boolean canEdit(Reader r, Article a) {
        return r.editor() && canReadWorkspace(r, a)
                && !a.is(ArticleStatus.REVIEW) && !a.is(ArticleStatus.ARCHIVED);
    }

    static boolean canSubmit(Reader r, Article a) {
        return r.editor() && canReadWorkspace(r, a) && a.is(ArticleStatus.DRAFT);
    }

    /** Проверку четырёх глаз сервис проверяет отдельно: здесь только роль и статус. */
    static boolean canPublish(Reader r, Article a, boolean fourEyes) {
        return r.manager() && (a.is(ArticleStatus.REVIEW) || (!fourEyes && a.is(ArticleStatus.DRAFT)));
    }

    static boolean canReject(Reader r, Article a) {
        return r.manager() && a.is(ArticleStatus.REVIEW);
    }

    static boolean canArchive(Reader r, Article a) {
        return r.manager() && !a.is(ArticleStatus.ARCHIVED);
    }

    static boolean canUnarchive(Reader r, Article a) {
        return r.manager() && a.is(ArticleStatus.ARCHIVED);
    }

    static boolean canRequestReview(Reader r, Article a) {
        return r.manager() && a.is(ArticleStatus.PUBLISHED);
    }

    static boolean canConfirmReview(Reader r, Article a) {
        return r.manager() && (a.is(ArticleStatus.REVIEW_DUE) || a.is(ArticleStatus.PUBLISHED));
    }

    static KnowledgeDtos.Permissions permissions(Reader r, Article a, boolean fourEyes) {
        boolean workspace = canReadWorkspace(r, a);
        return new KnowledgeDtos.Permissions(
                canEdit(r, a),
                canSubmit(r, a),
                canPublish(r, a, fourEyes),
                canReject(r, a),
                canArchive(r, a),
                canUnarchive(r, a),
                canRequestReview(r, a),
                canConfirmReview(r, a),
                workspace,
                canEdit(r, a));
    }
}
