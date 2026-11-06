package ru.easyitsm.tickets.api;

import java.util.List;
import java.util.UUID;

/**
 * Связи заявок (TCK-05) для других модулей: проблема со своими инцидентами (PRB-01), крупный инцидент с дочерними
 * (MIM, INC-09), изменение с источником. Вызов идёт в арендаторе вызывающего, права пользователя не проверяются.
 * <p>
 * Виды — с точки зрения {@code ticketId}: {@code parent_of}, {@code child_of}, {@code duplicate_of},
 * {@code duplicated_by}, {@code blocks}, {@code blocked_by}, {@code caused_by}, {@code causes}, {@code relates_to}.
 */
public interface TicketLinks {

    /**
     * Связывает заявки и пишет системную запись в ленты обеих. Связь с собой и цикл родителей —
     * {@code link_cycle}, неизвестная заявка — 404.
     *
     * @param actorId от чьего имени, null — система
     * @return true — связь создана, false — такая уже была
     */
    boolean link(UUID ticketId, UUID otherId, String kind, UUID actorId);

    /** Связи заявки в порядке создания, вид — с её точки зрения. */
    List<TicketLinkRef> links(UUID ticketId);

    /** Связь с точки зрения заявки: вид и другая сторона. */
    record TicketLinkRef(UUID linkId, String kind, UUID otherId) {
    }
}
