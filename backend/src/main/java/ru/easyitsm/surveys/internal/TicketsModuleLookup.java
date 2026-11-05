package ru.easyitsm.surveys.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ru.easyitsm.tickets.api.TicketQueries;

/** Адаптер к {@code ru.easyitsm.tickets.api}: пока модуль заявок не подключён, заявок нет. */
@Component
class TicketsModuleLookup implements TicketLookup {

    private final ObjectProvider<TicketQueries> queries;

    TicketsModuleLookup(ObjectProvider<TicketQueries> queries) {
        this.queries = queries;
    }

    @Override
    public Optional<TicketFacts> find(UUID ticketId) {
        TicketQueries q = queries.getIfAvailable();
        if (q == null) {
            return Optional.empty();
        }
        return q.find(ticketId).map(t -> new TicketFacts(t.id(), t.requesterId(), t.createdForId(),
                t.statusCategory().code()));
    }
}
