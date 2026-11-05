package ru.easyitsm.catalog.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ru.easyitsm.platform.id.Ids;

/** Модуль заявок в памяти для тестов каталога. */
class FakeTickets implements TicketGateway {

    final Map<UUID, NewTicket> created = new ConcurrentHashMap<>();
    final Map<UUID, String> status = new ConcurrentHashMap<>();
    final Map<UUID, String> reasons = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Override
    public TicketRef create(NewTicket t) {
        UUID id = Ids.newId();
        created.put(id, t);
        String s = t.awaitingApproval() ? "pending_approval" : "new";
        status.put(id, s);
        String prefix = t.type().equals("incident") ? "INC-" : "REQ-";
        return new TicketRef(id, prefix + String.format("%06d", seq.incrementAndGet()), s);
    }

    @Override
    public void applyApprovalOutcome(UUID ticketId, boolean approved, String reason) {
        status.put(ticketId, approved ? "approved" : "rejected");
        if (reason != null) {
            reasons.put(ticketId, reason);
        }
    }

    @Override
    public Optional<TicketInfo> find(UUID ticketId) {
        NewTicket t = created.get(ticketId);
        return t == null ? Optional.empty()
                : Optional.of(new TicketInfo(ticketId, "?", t.title(), "new", t.requesterId(), t.createdForId()));
    }
}
