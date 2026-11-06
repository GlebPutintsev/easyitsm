package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.TicketLinks;

/** Связи заявок для других модулей ({@link TicketLinks}): те же записи и лента, что у REST {@code /links}. */
@Service
class TicketLinksService implements TicketLinks {

    private static final Set<String> KINDS = Set.of("parent_of", "child_of", "duplicate_of", "duplicated_by", "blocks",
            "blocked_by", "caused_by", "causes", "relates_to");

    private final TicketStore store;
    private final Links links;
    private final Feed feed;

    TicketLinksService(TicketStore store, Links links, Feed feed) {
        this.store = store;
        this.links = links;
        this.feed = feed;
    }

    @Override
    @Transactional
    public boolean link(UUID ticketId, UUID otherId, String kind, UUID actorId) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("invalid_link_kind", "Неизвестный вид связи");
        }
        TicketRow t = store.find(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        TicketRow other = store.find(otherId).orElseThrow(() -> ApiException.notFound("Заявка"));
        UUID id;
        try {
            id = links.add(t.id, other.id, kind, actorId);
        } catch (ApiException e) {
            if ("link_exists".equals(e.code())) {
                return false;
            }
            throw e;
        }
        Instant now = Sql.now();
        feed.add(t.id, "system", "internal", actorId, false, "",
                Map.of("event", "linked", "kind", kind, "ticketId", other.id.toString(), "number", other.number),
                List.of(), now);
        String inverse = links.list(other.id).stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow().kind();
        feed.add(other.id, "system", "internal", actorId, false, "",
                Map.of("event", "linked", "kind", inverse, "ticketId", t.id.toString(), "number", t.number),
                List.of(), now);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketLinkRef> links(UUID ticketId) {
        return links.list(ticketId).stream().map(l -> new TicketLinkRef(l.id(), l.kind(), l.otherId())).toList();
    }
}
