package ru.easyitsm.surveys.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** До слияния модуля tickets заявок нет, и оценить нечего. Заменяется адаптером к tickets.api. */
@Component
class PendingTicketLookup implements TicketLookup {

    @Override
    public Optional<TicketFacts> find(UUID ticketId) {
        return Optional.empty();
    }
}
