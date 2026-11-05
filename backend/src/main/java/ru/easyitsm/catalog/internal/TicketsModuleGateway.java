package ru.easyitsm.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Адаптер к {@code ru.easyitsm.tickets.api}. Пока реализация модуля заявок не подключена,
 * каталог работает, а заказ отвечает 503 {@code tickets_unavailable}.
 */
@Component
class TicketsModuleGateway implements TicketGateway {

    private final ObjectProvider<TicketCommands> commands;
    private final ObjectProvider<TicketQueries> queries;

    TicketsModuleGateway(ObjectProvider<TicketCommands> commands, ObjectProvider<TicketQueries> queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @Override
    public TicketRef create(NewTicket ticket) {
        var t = ticket;
        var ref = commands().create(new ru.easyitsm.tickets.api.NewTicket(TicketType.fromCode(t.type()), t.title(),
                t.description(), t.requesterId(), t.createdForId(), null, Channel.PORTAL, impact(t.impact()), null,
                null, null, null, t.catalogItemId(), null, null, t.custom(), t.awaitingApproval(), t.requesterId()));
        return new TicketRef(ref.id(), ref.number(), ref.statusCode());
    }

    @Override
    public void applyApprovalOutcome(UUID ticketId, boolean approved, String reason) {
        commands().applyApprovalOutcome(ticketId, approved, reason);
    }

    @Override
    public Optional<TicketInfo> find(UUID ticketId) {
        TicketQueries q = queries.getIfAvailable();
        if (q == null) {
            return Optional.empty();
        }
        return q.find(ticketId).map(s -> new TicketInfo(s.id(), s.number(), s.title(), s.statusCategory().code(),
                s.requesterId(), s.createdForId()));
    }

    /** «Только я / подразделение / все» из формы портала (10.5) в уровень влияния модуля заявок. */
    static Level impact(String code) {
        return switch (code == null ? "only_me" : code) {
            case "everyone" -> Level.HIGH;
            case "department" -> Level.MEDIUM;
            default -> Level.LOW;
        };
    }

    private TicketCommands commands() {
        TicketCommands c = commands.getIfAvailable();
        if (c == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tickets_unavailable",
                    "Создание заявок пока недоступно");
        }
        return c;
    }
}
