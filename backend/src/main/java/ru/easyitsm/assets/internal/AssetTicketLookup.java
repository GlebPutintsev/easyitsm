package ru.easyitsm.assets.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Заявки из модуля tickets: связь актива с заявкой видна и меняется только тем, кто видит заявку. */
@Component
class AssetTicketLookup {

    private final TicketQueries tickets;

    AssetTicketLookup(TicketQueries tickets) {
        this.tickets = tickets;
    }

    /** Заявка, видимая текущему пользователю по правилам модуля заявок; иначе пусто. */
    Optional<AssetDtos.TicketInfo> visible(UUID ticketId) {
        return tickets.findVisible(ticketId).map(AssetTicketLookup::info);
    }

    Optional<AssetDtos.TicketInfo> visibleByNumber(String number) {
        return tickets.findByNumber(number.strip().toUpperCase()).flatMap(t -> visible(t.id()));
    }

    static AssetDtos.TicketInfo info(TicketSnapshot t) {
        return new AssetDtos.TicketInfo(t.id(), t.number(), t.title(), t.statusCategory().code());
    }
}
