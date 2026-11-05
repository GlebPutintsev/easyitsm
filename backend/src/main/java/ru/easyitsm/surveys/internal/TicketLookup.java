package ru.easyitsm.surveys.internal;

import java.util.Optional;
import java.util.UUID;

/** Что опросу нужно знать о заявке. Реализация читает {@code ru.easyitsm.tickets.api.TicketQueries}. */
interface TicketLookup {

    Optional<TicketFacts> find(UUID ticketId);

    /** @param statusCategory код категории статуса: closed, resolved, in_progress… */
    record TicketFacts(UUID id, UUID requesterId, UUID createdForId, String statusCategory) {

        boolean participant(UUID user) {
            return user.equals(requesterId) || user.equals(createdForId);
        }
    }
}
