package ru.easyitsm.tickets.api;

import java.util.Optional;
import java.util.UUID;

/** Чтение заявок другими модулями в пределах арендатора (RLS), без проверки прав пользователя. */
public interface TicketQueries {

    Optional<TicketSnapshot> find(UUID ticketId);

    /** По номеру вида {@code INC-000123}. */
    Optional<TicketSnapshot> findByNumber(String number);
}
