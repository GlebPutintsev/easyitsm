package ru.easyitsm.knowledge.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Команды и ответы API базы знаний. */
final class KnowledgeDtos {

    static final String AUDIENCES = "all|employees|agents|group";
    /** Ограничение текста статьи: 500 тысяч символов достаточно для длинной инструкции с таблицами. */
    static final int MAX_BODY = 500_000;

    private KnowledgeDtos() {
    }

    // --- команды ---

    record CreateArticle(
            @NotBlank @Size(max = 255) String title,
            @Size(max = MAX_BODY) String body,
            @Pattern(regexp = "blank|howto|solution|faq|known_error") String template,
            UUID categoryId,
            UUID serviceId,
            @Pattern(regexp = AUDIENCES) String audience,
            UUID audienceGroupId) {
    }

    /** Частичное изменение рабочей копии: null означает «не менять». */
    record UpdateArticle(
            @Size(min = 1, max = 255) String title,
            @Size(max = MAX_BODY) String body,
            UUID categoryId,
            Boolean clearCategory,
            UUID serviceId,
            Boolean clearService,
            @Pattern(regexp = AUDIENCES) String audience,
            UUID audienceGroupId,
            UUID ownerId) {
    }

    /**
     * KB-05: черновик из решённой заявки. Номер заявки берётся из модуля заявок, {@code ticketRef} оставлен
     * для совместимости и не используется. Пустая тема — тема заявки. Разделы агент заполняет в форме.
     */
    record CreateFromTicket(
            @NotNull UUID ticketId,
            @Size(max = 64) String ticketRef,
            @Size(max = 255) String title,
            @Size(max = 50_000) String symptoms,
            @Size(max = 50_000) String cause,
            @NotBlank @Size(max = 50_000) String solution,
            UUID categoryId) {
    }

    record Submit(UUID reviewerId, @Size(max = 2000) String comment, Boolean confirmNoSecrets) {
    }

    record Publish(
            @Pattern(regexp = AUDIENCES) String audience,
            UUID audienceGroupId,
            Instant reviewAt,
            @Size(max = 2000) String notes) {
    }

    record Reject(@NotBlank @Size(max = 2000) String comment) {
    }

    record ConfirmReview(Instant reviewAt) {
    }

    record Feedback(@NotNull Boolean helpful, @Size(max = 2000) String comment) {
    }

    record CreateCategory(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                          Integer sortOrder) {
    }

    record UpdateCategory(@Size(min = 1, max = 120) String name, @Size(max = 1000) String description,
                          Integer sortOrder) {
    }

    // --- ответы ---

    record Permissions(
            boolean edit,
            boolean submit,
            boolean publish,
            boolean reject,
            boolean archive,
            boolean unarchive,
            boolean requestReview,
            boolean confirmReview,
            boolean viewHistory,
            boolean restore) {
    }

    record FeedbackSummary(long helpful, long notHelpful, Boolean mine) {
    }

    /**
     * Статья с содержимым одной версии. {@code shownVersionNo} — какая версия в {@code title} и {@code body}.
     * Служебные поля (владелец, комментарий проверки, источник) видны только при доступе к рабочей копии.
     */
    record ArticleDetails(
            UUID id,
            String key,
            String status,
            String title,
            String body,
            int shownVersionNo,
            Integer currentVersionNo,
            Integer publishedVersionNo,
            UUID categoryId,
            UUID serviceId,
            String audience,
            UUID audienceGroupId,
            UUID ownerId,
            UUID reviewerId,
            String reviewComment,
            Instant reviewAt,
            Instant publishedAt,
            UUID sourceTicketId,
            String sourceTicketRef,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy,
            long version,
            boolean fourEyes,
            FeedbackSummary feedback,
            Permissions permissions) {
    }

    record VersionSummary(int versionNo, String title, String notes, Integer restoredFrom, boolean published,
                          boolean current, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
    }

    record VersionDetails(int versionNo, String title, String body, String notes, Integer restoredFrom,
                          boolean published, boolean current, Instant createdAt, UUID createdBy, Instant updatedAt,
                          UUID updatedBy) {
    }

    record ArticleListItem(
            UUID id,
            String key,
            String title,
            String snippet,
            String status,
            String audience,
            UUID categoryId,
            UUID ownerId,
            int currentVersionNo,
            Integer publishedVersionNo,
            Instant publishedAt,
            Instant reviewAt,
            Instant updatedAt) {
    }

    record CategoryView(UUID id, String name, String description, int sortOrder, long version) {
    }

    record TemplateView(String code, String body) {
    }

    /** Найденные признаки секретов (10.12 «проверка секретов»). */
    record SecretFinding(String kind, int line) {
    }

    record SecretsDetected(List<SecretFinding> findings) {
    }
}
