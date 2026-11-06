package ru.easyitsm.assets.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.assets.api.AssetQueries;
import ru.easyitsm.assets.api.AssetState;
import ru.easyitsm.assets.api.AssetSummary;
import ru.easyitsm.assets.events.AssetAssigned;
import ru.easyitsm.assets.events.AssetRetired;
import ru.easyitsm.assets.internal.AssetDtos.AssetView;
import ru.easyitsm.assets.internal.AssetDtos.Named;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Оборудование (AST-01) и его связи с сотрудником, КЕ и заявками (AST-05). */
@Service
class AssetService implements AssetQueries {

    /** Сколько дней до конца гарантии считается «гарантия заканчивается» в сводке. */
    static final int WARRANTY_SOON_DAYS = 30;

    private final AssetRepository assets;
    private final LocationRepository locations;
    private final VendorRepository vendors;
    private final ContractRepository contracts;
    private final AssetSearch search;
    private final AssetHistory history;
    private final LicenseAssignments assignments;
    private final People people;
    private final AssetTicketLookup tickets;
    private final CiLookup cis;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AssetService(AssetRepository assets, LocationRepository locations, VendorRepository vendors,
                 ContractRepository contracts, AssetSearch search, AssetHistory history,
                 LicenseAssignments assignments, People people, AssetTicketLookup tickets, CiLookup cis, Notifier notifier,
                 ApplicationEventPublisher events, Optional<Clock> clock) {
        this.assets = assets;
        this.locations = locations;
        this.vendors = vendors;
        this.contracts = contracts;
        this.search = search;
        this.history = history;
        this.assignments = assignments;
        this.people = people;
        this.tickets = tickets;
        this.cis = cis;
        this.notifier = notifier;
        this.events = events;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ AssetQueries

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> heldBy(UUID userId) {
        return assets.findHeldBy(userId).stream().map(Asset::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> linkedToCi(UUID ciId) {
        return assets.findByCi(ciId).stream().map(Asset::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetSummary> linkedToTicket(UUID ticketId) {
        return assets.findByTicket(ticketId).stream().map(Asset::toSummary).toList();
    }

    // ------------------------------------------------------------------ чтение

    @Transactional(readOnly = true)
    public CursorPage<AssetDtos.AssetRow> list(AssetSearch.Filter filter, PageParams page) {
        if (filter.ticketId() != null && tickets.visible(filter.ticketId()).isEmpty()) {
            throw ApiException.notFound("Заявка");
        }
        KeysetCursor after = page.keyset();
        List<AssetSearch.Row> rows = search.page(filter, after == null ? null : after.id(), page.limit() + 1);
        Map<UUID, String> names = people.names(rows.stream().map(AssetSearch.Row::holderId).toList());
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.id().toString(), r.id()))
                .map(r -> new AssetDtos.AssetRow(r.id(), r.tag(), r.name(), r.kind(), r.model(), r.serial(),
                        r.state(), named(r.holderId(), names), r.locationId() == null ? null
                        : new Named(r.locationId(), r.locationName()), r.warrantyTo(), r.cost(), r.currency()));
    }

    @Transactional(readOnly = true)
    public AssetDtos.AssetStats stats() {
        return search.stats(today(), WARRANTY_SOON_DAYS);
    }

    @Transactional(readOnly = true)
    public AssetView get(UUID id) {
        return view(find(id));
    }

    /** Оборудование текущего пользователя: «Моё оборудование» на портале. */
    @Transactional(readOnly = true)
    public List<AssetDtos.AssetRow> mine() {
        UUID me = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        Map<UUID, String> names = people.names(List.of(me));
        return assets.findHeldBy(me).stream().map(a -> new AssetDtos.AssetRow(a.getId(), a.tag(), a.name(), a.kind(),
                a.model(), a.serial(), a.state().code(), named(me, names), location(a.locationId()), a.warrantyTo(),
                null, a.currency())).toList();
    }

    @Transactional(readOnly = true)
    public List<AssetDtos.AssetEventView> history(UUID id) {
        find(id);
        List<AssetHistory.Entry> entries = history.list(id, 200);
        Set<UUID> users = new HashSet<>();
        entries.forEach(e -> {
            users.add(e.fromHolder());
            users.add(e.toHolder());
            users.add(e.actorId());
        });
        users.remove(null);
        Map<UUID, String> names = people.names(users);
        List<AssetDtos.AssetEventView> out = new ArrayList<>();
        for (AssetHistory.Entry e : entries) {
            // Заявку, которую пользователь не видит, в истории не раскрываем: ни номера, ни темы.
            AssetDtos.TicketInfo ticket = e.ticketId() == null ? null : tickets.visible(e.ticketId()).orElse(null);
            out.add(new AssetDtos.AssetEventView(e.id(), e.kind(), e.fromState(), e.toState(),
                    named(e.fromHolder(), names), named(e.toHolder(), names), ticket, e.comment(),
                    named(e.actorId(), names), e.at()));
        }
        return out;
    }

    /** Заявки актива, видимые текущему пользователю. */
    @Transactional(readOnly = true)
    public List<AssetDtos.TicketInfo> tickets(UUID id) {
        find(id);
        return history.tickets(id).stream().map(tickets::visible).flatMap(Optional::stream).toList();
    }

    // ------------------------------------------------------------------ изменение

    @Transactional
    public AssetView create(AssetDtos.AssetFields f) {
        if (f.tag() == null || f.tag().isBlank() || f.name() == null || f.name().isBlank() || f.kind() == null) {
            throw ApiException.badRequest("required", "Нужны инвентарный номер, название и тип");
        }
        var a = new Asset(f.tag().strip(), f.name().strip(), f.kind());
        apply(a, f);
        a = save(a);
        history.record(a.getId(), new AssetHistory.Change("created", null, a.state().code(), null, null, null, null),
                now());
        return view(a);
    }

    @Transactional
    public AssetView update(UUID id, String ifMatch, AssetDtos.AssetFields f) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (a.state() == AssetState.RETIRED) {
            throw ApiException.conflict("invalid_state", "Списанный актив не изменяется");
        }
        if (f.tag() != null) {
            a.setTag(f.tag().strip());
        }
        if (f.name() != null) {
            a.setName(f.name().strip());
        }
        if (f.kind() != null) {
            a.setKind(f.kind());
        }
        apply(a, f);
        a = save(a);
        history.record(a.getId(), AssetHistory.Change.of("updated"), now());
        return view(a);
    }

    private void apply(Asset a, AssetDtos.AssetFields f) {
        a.setManufacturer(f.clears("manufacturer") ? null : f.manufacturer() != null
                ? AssetDtos.blankToNull(f.manufacturer()) : a.manufacturer());
        a.setModel(f.clears("model") ? null : f.model() != null ? AssetDtos.blankToNull(f.model()) : a.model());
        a.setSerial(f.clears("serial") ? null : f.serial() != null ? AssetDtos.blankToNull(f.serial()) : a.serial());
        a.setLocationId(f.clears("locationId") ? null : f.locationId() != null
                ? requireLocation(f.locationId()) : a.locationId());
        if (f.clears("ciId")) {
            a.setCiId(null);
        } else if (f.ciId() != null && !f.ciId().equals(a.ciId())) {
            if (!cis.exists(f.ciId())) {
                throw ApiException.badRequest("unknown_ci", "Конфигурационная единица не найдена");
            }
            a.setCiId(f.ciId());
        }
        a.setVendorId(f.clears("vendorId") ? null : f.vendorId() != null ? requireVendor(f.vendorId()) : a.vendorId());
        a.setContractId(f.clears("contractId") ? null : f.contractId() != null
                ? requireContract(f.contractId()) : a.contractId());
        a.setPurchasedOn(f.clears("purchasedOn") ? null : f.purchasedOn() != null ? f.purchasedOn() : a.purchasedOn());
        a.setWarrantyTo(f.clears("warrantyTo") ? null : f.warrantyTo() != null ? f.warrantyTo() : a.warrantyTo());
        a.setServiceLifeMonths(f.clears("serviceLifeMonths") ? null : f.serviceLifeMonths() != null
                ? f.serviceLifeMonths() : a.serviceLifeMonths());
        a.setCost(f.clears("cost") ? null : f.cost() != null ? f.cost() : a.cost());
        if (f.currency() != null) {
            a.setCurrency(f.currency());
        }
        if (f.notes() != null) {
            a.setNotes(f.notes());
        }
    }

    @Transactional
    public AssetView issue(UUID id, String ifMatch, AssetDtos.Assign cmd) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        people.requireActive(cmd.holderId(), "holderId");
        String from = a.state().code();
        UUID previous = a.holderId();
        a.issue(cmd.holderId(), cmd.locationId() == null ? null : requireLocation(cmd.locationId()));
        a = save(a);
        history.record(id, new AssetHistory.Change("issued", from, a.state().code(), previous, a.holderId(), null,
                cmd.comment()), now());
        assigned(a, previous);
        return view(a);
    }

    @Transactional
    public AssetView transfer(UUID id, String ifMatch, AssetDtos.Assign cmd) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        people.requireActive(cmd.holderId(), "holderId");
        UUID previous = a.holderId();
        a.transfer(cmd.holderId(), cmd.locationId() == null ? null : requireLocation(cmd.locationId()));
        a = save(a);
        history.record(id, new AssetHistory.Change("transferred", a.state().code(), a.state().code(), previous,
                a.holderId(), null, cmd.comment()), now());
        assigned(a, previous);
        return view(a);
    }

    @Transactional
    public AssetView giveBack(UUID id, String ifMatch, AssetDtos.Move cmd) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        String from = a.state().code();
        UUID previous = a.holderId();
        a.giveBack(cmd.locationId() == null ? null : requireLocation(cmd.locationId()));
        a = save(a);
        history.record(id, new AssetHistory.Change("returned", from, a.state().code(), previous, null, null,
                cmd.comment()), now());
        return view(a);
    }

    @Transactional
    public AssetView repair(UUID id, String ifMatch, AssetDtos.Move cmd) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        String from = a.state().code();
        a.sendToRepair();
        if (cmd.locationId() != null) {
            a.setLocationId(requireLocation(cmd.locationId()));
        }
        a = save(a);
        history.record(id, new AssetHistory.Change("repair", from, a.state().code(), a.holderId(), a.holderId(), null,
                cmd.comment()), now());
        return view(a);
    }

    @Transactional
    public AssetView retire(UUID id, String ifMatch, AssetDtos.Retire cmd) {
        Asset a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        String from = a.state().code();
        UUID previous = a.holderId();
        Instant at = now();
        a.retire(cmd.reason().strip(), at);
        a = save(a);
        int released = assignments.releaseAsset(id);
        String comment = cmd.reason().strip() + (released > 0 ? " (освобождено лицензий: " + released + ")" : "");
        history.record(id, new AssetHistory.Change("retired", from, a.state().code(), previous, null, null, comment),
                at);
        events.publishEvent(new AssetRetired(a.getTenantId(), at, CurrentActor.userId().orElse(null), a.getId(),
                a.tag(), cmd.reason().strip()));
        return view(a);
    }

    // ------------------------------------------------------------------ заявки (AST-05)

    @Transactional
    public List<AssetDtos.TicketInfo> linkTicket(UUID id, AssetDtos.LinkTicket cmd) {
        Asset a = find(id);
        AssetDtos.TicketInfo ticket;
        if (cmd.ticketId() != null) {
            ticket = tickets.visible(cmd.ticketId()).orElseThrow(() -> ApiException.notFound("Заявка"));
        } else if (cmd.number() != null && !cmd.number().isBlank()) {
            ticket = tickets.visibleByNumber(cmd.number()).orElseThrow(() -> ApiException.notFound("Заявка"));
        } else {
            throw ApiException.badRequest("required", "Укажите заявку");
        }
        if (history.link(a.getId(), ticket.id(), now())) {
            history.record(a.getId(), new AssetHistory.Change("ticket_linked", null, null, null, null, ticket.id(),
                    null), now());
        }
        return tickets(id);
    }

    @Transactional
    public void unlinkTicket(UUID id, UUID ticketId) {
        Asset a = find(id);
        if (tickets.visible(ticketId).isEmpty()) {
            throw ApiException.notFound("Заявка");
        }
        if (!history.unlink(a.getId(), ticketId)) {
            throw ApiException.notFound("Связь");
        }
        history.record(a.getId(), new AssetHistory.Change("ticket_unlinked", null, null, null, null, ticketId, null),
                now());
    }

    // ------------------------------------------------------------------ служебное

    Asset find(UUID id) {
        // Чужой арендатор отсекается RLS: 404, а не 403 (AT-01).
        return assets.findById(id).orElseThrow(() -> ApiException.notFound("Актив"));
    }

    private Asset save(Asset a) {
        try {
            return assets.save(a);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_tag", "Инвентарный номер уже занят");
        }
    }

    private void assigned(Asset a, UUID previous) {
        UUID actor = CurrentActor.userId().orElse(null);
        events.publishEvent(new AssetAssigned(a.getTenantId(), now(), actor, a.getId(), a.tag(), a.name(),
                a.holderId(), previous));
        if (!a.holderId().equals(actor)) {
            notifier.notify(a.holderId(), "assets", "За вами закреплено оборудование",
                    a.name() + ", инв. № " + a.tag(), "/portal/assets", false);
        }
    }

    private UUID requireLocation(UUID id) {
        return locations.findById(id).map(l -> id)
                .orElseThrow(() -> ApiException.badRequest("unknown_location", "Местоположение не найдено"));
    }

    private UUID requireVendor(UUID id) {
        return vendors.findById(id).map(v -> id)
                .orElseThrow(() -> ApiException.badRequest("unknown_vendor", "Поставщик не найден"));
    }

    private UUID requireContract(UUID id) {
        return contracts.findById(id).map(c -> id)
                .orElseThrow(() -> ApiException.badRequest("unknown_contract", "Договор не найден"));
    }

    private Named location(UUID id) {
        return id == null ? null : locations.findById(id).map(l -> new Named(id, l.toView().name())).orElse(null);
    }

    private AssetView view(Asset a) {
        LocalDate today = today();
        Map<UUID, String> names = people.names(a.holderId() == null ? List.of() : List.of(a.holderId()));
        AssetDtos.CiInfo ci = a.ciId() == null ? null : cis.find(List.of(a.ciId())).getOrDefault(a.ciId(),
                new AssetDtos.CiInfo(a.ciId(), null, null, null, null));
        Named vendor = a.vendorId() == null ? null
                : vendors.findById(a.vendorId()).map(v -> new Named(v.getId(), v.name())).orElse(null);
        AssetDtos.ContractRef contract = a.contractId() == null ? null : contracts.findById(a.contractId())
                .map(c -> new AssetDtos.ContractRef(c.getId(), c.number(), c.title())).orElse(null);
        LocalDate eol = a.endOfLife();
        return new AssetView(a.getId(), a.tag(), a.name(), a.kind(), a.manufacturer(), a.model(), a.serial(),
                a.state().code(), named(a.holderId(), names), location(a.locationId()), ci, vendor, contract,
                a.purchasedOn(), a.warrantyTo(), a.warrantyTo() != null && a.warrantyTo().isBefore(today),
                a.serviceLifeMonths(), eol, eol != null && !eol.isAfter(today), a.cost(), a.currency(), a.notes(),
                a.retireReason(), a.retiredAt(), a.getCreatedAt(), a.getUpdatedAt(), a.getVersion());
    }

    static Named named(UUID id, Map<UUID, String> names) {
        return id == null ? null : new Named(id, names.get(id));
    }

    private Instant now() {
        return clock.instant();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
