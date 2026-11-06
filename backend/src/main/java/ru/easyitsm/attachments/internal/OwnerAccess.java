package ru.easyitsm.attachments.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.knowledge.api.ArticleQueries;
import ru.easyitsm.tickets.api.TicketQueries;

/**
 * Права текущего пользователя на владельца файла. Решает модуль-владелец: файл виден тому, кто видит заявку
 * или статью, внутренний файл заявки — только поддержке и аудитору.
 */
@Component
class OwnerAccess {

    /**
     * @param canAdd         может прикрепить публичный файл
     * @param canAddInternal может прикрепить внутренний файл
     * @param canSeeInternal видит внутренние файлы
     * @param canManage      может удалить чужой файл (работает с заявкой или правит статью)
     */
    record Rights(boolean canAdd, boolean canAddInternal, boolean canSeeInternal, boolean canManage) {
    }

    static final String TICKET = "ticket";
    static final String ARTICLE = "article";

    private final TicketQueries tickets;
    private final ArticleQueries articles;

    OwnerAccess(TicketQueries tickets, ArticleQueries articles) {
        this.tickets = tickets;
        this.articles = articles;
    }

    /** Пусто — владельца нет или он не виден: 404. */
    Optional<Rights> rights(String ownerType, UUID ownerId) {
        return switch (ownerType) {
            case TICKET -> tickets.permissions(ownerId).map(p -> new Rights(
                    p.canComment() && !p.closed(), p.canAddInternal() && !p.closed(), p.canSeeInternal(),
                    p.canAddInternal()));
            case ARTICLE -> articles.permissions(ownerId).filter(p -> p.canRead())
                    .map(p -> new Rights(p.canEdit(), false, false, p.canEdit()));
            default -> Optional.empty();
        };
    }
}
