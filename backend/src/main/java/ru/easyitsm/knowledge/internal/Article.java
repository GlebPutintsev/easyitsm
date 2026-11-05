package ru.easyitsm.knowledge.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.knowledge.api.ArticleAudience;
import ru.easyitsm.knowledge.api.ArticleStatus;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/**
 * Статья знаний и её жизненный цикл (5.7). Содержимое лежит в {@link ArticleVersion}:
 * рабочая копия — {@code currentVersionNo}, читатели видят {@code publishedVersionNo}.
 * <p>
 * Переходы проверяют только состояние статьи. Права проверяет {@link ArticleAccess}.
 */
@Table("article")
class Article extends TenantEntity {

    private long number;
    private String status;
    private String title;
    private UUID categoryId;
    private String audience;
    private UUID audienceGroupId;
    private UUID ownerId;
    private UUID reviewerId;
    private String reviewComment;
    private int currentVersionNo;
    private Integer publishedVersionNo;
    private String publishedTitle;
    private Instant publishedAt;
    private Instant reviewAt;
    private Instant reviewRemindedAt;
    private UUID sourceTicketId;
    private String sourceTicketRef;

    protected Article() {
    }

    Article(long number, String title, UUID categoryId, ArticleAudience audience, UUID audienceGroupId, UUID ownerId) {
        this.number = number;
        this.status = ArticleStatus.DRAFT.code();
        this.title = title;
        this.categoryId = categoryId;
        this.ownerId = ownerId;
        this.currentVersionNo = 1;
        setAudience(audience, audienceGroupId);
    }

    // --- состояние ---

    ArticleStatus status() {
        return ArticleStatus.fromCode(status);
    }

    boolean is(ArticleStatus s) {
        return status().equals(s);
    }

    /** Опубликованная версия видна читателям: есть публикация и статья не в архиве. */
    boolean isReadable() {
        return publishedVersionNo != null && !is(ArticleStatus.ARCHIVED);
    }

    /**
     * Рабочую версию можно менять на месте, пока это черновик, который ещё не публиковался.
     * Иначе правка создаёт новую версию, а опубликованная остаётся у читателей.
     */
    boolean currentVersionIsMutable() {
        return is(ArticleStatus.DRAFT) && (publishedVersionNo == null || currentVersionNo > publishedVersionNo);
    }

    // --- правка ---

    void requireEditable() {
        if (is(ArticleStatus.REVIEW)) {
            throw ApiException.conflict("article_in_review", "Статья на проверке. Дождитесь решения или верните её на доработку");
        }
        if (is(ArticleStatus.ARCHIVED)) {
            throw ApiException.conflict("article_archived", "Статья в архиве. Сначала восстановите её из архива");
        }
    }

    /** Новая рабочая версия поверх замороженной: статья становится черновиком изменений. */
    int startNewVersion() {
        requireEditable();
        currentVersionNo++;
        status = ArticleStatus.DRAFT.code();
        reviewComment = null;
        return currentVersionNo;
    }

    void setTitle(String title) {
        this.title = title;
    }

    void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    void setAudience(ArticleAudience audience, UUID groupId) {
        if (audience == ArticleAudience.GROUP && groupId == null) {
            throw ApiException.badRequest("audience_group_required", "Для аудитории «Группа» укажите группу");
        }
        this.audience = audience.code();
        this.audienceGroupId = audience == ArticleAudience.GROUP ? groupId : null;
    }

    void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    void setSource(UUID ticketId, String ticketRef) {
        this.sourceTicketId = ticketId;
        this.sourceTicketRef = ticketRef;
    }

    // --- переходы ---

    void submit(UUID reviewerId) {
        require(ArticleStatus.DRAFT, "Отправить на проверку можно только черновик");
        this.status = ArticleStatus.REVIEW.code();
        this.reviewerId = reviewerId;
        this.reviewComment = null;
    }

    void reject(String comment) {
        require(ArticleStatus.REVIEW, "Вернуть на доработку можно только статью на проверке");
        this.status = ArticleStatus.DRAFT.code();
        this.reviewComment = comment;
    }

    /**
     * Публикация рабочей версии. Из черновика — только когда проверка четырёх глаз выключена,
     * это решает сервис.
     */
    void publish(Instant now, Instant reviewAt) {
        if (!is(ArticleStatus.REVIEW) && !is(ArticleStatus.DRAFT)) {
            throw invalid("Опубликовать можно статью на проверке");
        }
        if (!reviewAt.isAfter(now)) {
            throw ApiException.badRequest("invalid_review_at", "Срок пересмотра должен быть в будущем");
        }
        this.status = ArticleStatus.PUBLISHED.code();
        this.publishedVersionNo = currentVersionNo;
        this.publishedTitle = title;
        this.publishedAt = now;
        this.reviewAt = reviewAt;
        this.reviewRemindedAt = null;
        this.reviewComment = null;
    }

    void markReviewDue() {
        require(ArticleStatus.PUBLISHED, "На пересмотр переводится только опубликованная статья");
        this.status = ArticleStatus.REVIEW_DUE.code();
    }

    /** Статья актуальна: остаётся опубликованной с новым сроком пересмотра. */
    void confirmReview(Instant now, Instant nextReviewAt) {
        if (!is(ArticleStatus.REVIEW_DUE) && !is(ArticleStatus.PUBLISHED)) {
            throw invalid("Подтвердить актуальность можно у опубликованной статьи или статьи на пересмотре");
        }
        if (!nextReviewAt.isAfter(now)) {
            throw ApiException.badRequest("invalid_review_at", "Срок пересмотра должен быть в будущем");
        }
        this.status = ArticleStatus.PUBLISHED.code();
        this.reviewAt = nextReviewAt;
        this.reviewRemindedAt = null;
    }

    void markReminded(Instant now) {
        this.reviewRemindedAt = now;
    }

    void archive() {
        if (is(ArticleStatus.ARCHIVED)) {
            throw invalid("Статья уже в архиве");
        }
        this.status = ArticleStatus.ARCHIVED.code();
    }

    /** Из архива статья возвращается черновиком и снова проходит проверку перед публикацией. */
    void unarchive() {
        require(ArticleStatus.ARCHIVED, "Статья не в архиве");
        this.status = ArticleStatus.DRAFT.code();
        this.publishedVersionNo = null;
        this.publishedTitle = null;
        this.publishedAt = null;
        this.reviewAt = null;
        this.reviewRemindedAt = null;
    }

    private void require(ArticleStatus expected, String message) {
        if (!is(expected)) {
            throw invalid(message);
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.conflict("invalid_transition", message);
    }

    // --- чтение ---

    long number() {
        return number;
    }

    String key() {
        return "KB-" + number;
    }

    String title() {
        return title;
    }

    UUID categoryId() {
        return categoryId;
    }

    ArticleAudience audience() {
        return ArticleAudience.fromCode(audience);
    }

    UUID audienceGroupId() {
        return audienceGroupId;
    }

    UUID ownerId() {
        return ownerId;
    }

    UUID reviewerId() {
        return reviewerId;
    }

    String reviewComment() {
        return reviewComment;
    }

    int currentVersionNo() {
        return currentVersionNo;
    }

    Integer publishedVersionNo() {
        return publishedVersionNo;
    }

    String publishedTitle() {
        return publishedTitle;
    }

    Instant publishedAt() {
        return publishedAt;
    }

    Instant reviewAt() {
        return reviewAt;
    }

    UUID sourceTicketId() {
        return sourceTicketId;
    }

    String sourceTicketRef() {
        return sourceTicketRef;
    }
}
