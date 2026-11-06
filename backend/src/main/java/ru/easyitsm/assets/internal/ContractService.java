package ru.easyitsm.assets.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.assets.events.ContractExpiring;
import ru.easyitsm.assets.internal.AssetDtos.ContractView;
import ru.easyitsm.assets.internal.AssetDtos.Named;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/** Договоры, поставщики и местоположения (AST-04). */
@Service
class ContractService {

    private final ContractRepository contracts;
    private final VendorRepository vendors;
    private final LocationRepository locations;
    private final AssetSearch search;
    private final People people;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ContractService(ContractRepository contracts, VendorRepository vendors, LocationRepository locations,
                    AssetSearch search, People people, JdbcClient jdbc, Notifier notifier,
                    ApplicationEventPublisher events, Optional<Clock> clock) {
        this.contracts = contracts;
        this.vendors = vendors;
        this.locations = locations;
        this.search = search;
        this.people = people;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.events = events;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ договоры

    /** Договоры арендатора: ближайшие к окончанию сверху, бессрочные и расторгнутые в конце. */
    @Transactional(readOnly = true)
    public List<ContractView> list(String q, String status, UUID vendorId) {
        List<Contract> all = contracts.findAll();
        LocalDate today = today();
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase();
        List<Contract> picked = all.stream()
                .filter(c -> needle == null || c.number().toLowerCase().contains(needle)
                        || c.title().toLowerCase().contains(needle))
                .filter(c -> status == null || status.equals(c.status(today)))
                .filter(c -> vendorId == null || vendorId.equals(c.vendorId()))
                .sorted(Comparator.comparing(Contract::terminated)
                        .thenComparing(Contract::endsOn, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Contract::number))
                .toList();
        return views(picked, today);
    }

    @Transactional(readOnly = true)
    public ContractView get(UUID id) {
        return views(List.of(find(id)), today()).getFirst();
    }

    @Transactional
    public ContractView create(AssetDtos.ContractFields f) {
        if (f.number() == null || f.number().isBlank() || f.title() == null || f.title().isBlank()
                || f.kind() == null || f.startsOn() == null) {
            throw ApiException.badRequest("required", "Нужны номер, предмет, вид и дата начала договора");
        }
        check(f);
        var c = new Contract(f.number().strip(), f.title().strip(), f.kind(), f.startsOn());
        c.apply(f);
        return get(save(c).getId());
    }

    @Transactional
    public ContractView update(UUID id, String ifMatch, AssetDtos.ContractFields f) {
        Contract c = find(id);
        ETags.requireMatch(ifMatch, c.getVersion());
        check(f);
        c.apply(f);
        return get(save(c).getId());
    }

    private void check(AssetDtos.ContractFields f) {
        if (f.vendorId() != null && vendors.findById(f.vendorId()).isEmpty()) {
            throw ApiException.badRequest("unknown_vendor", "Поставщик не найден");
        }
        people.requireKnown(f.ownerId(), "ownerId");
    }

    private Contract save(Contract c) {
        try {
            return contracts.save(c);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_number", "Договор с таким номером уже есть");
        }
    }

    private Contract find(UUID id) {
        return contracts.findById(id).orElseThrow(() -> ApiException.notFound("Договор"));
    }

    /** Наступил срок напоминания о продлении: событие и уведомление с письмом ответственному. */
    @Transactional
    public void remindBySchedule(UUID id, LocalDate today) {
        contracts.findById(id).filter(c -> c.endsOn() != null && !c.terminated()).ifPresent(c -> {
            c.markReminded();
            contracts.save(c);
            int days = (int) ChronoUnit.DAYS.between(today, c.endsOn());
            events.publishEvent(new ContractExpiring(c.getTenantId(), c.getId(), c.number(), c.title(), c.endsOn(),
                    days, c.autoRenew(), c.ownerId()));
            if (c.ownerId() != null) {
                String renew = c.autoRenew() ? "Продлится автоматически, если не расторгнуть." : "Нужно решение о продлении.";
                notifier.notify(c.ownerId(), "assets", "Заканчивается договор № " + c.number(),
                        c.title() + ". Действует до " + c.endsOn() + " (осталось дней: " + days + "). " + renew,
                        "/agent/assets/contracts/" + c.getId(), true);
            }
        });
    }

    private List<ContractView> views(List<Contract> list, LocalDate today) {
        Map<UUID, String> vendorNames = new HashMap<>();
        vendors.findAllById(list.stream().map(Contract::vendorId).filter(Objects::nonNull).distinct().toList())
                .forEach(v -> vendorNames.put(v.getId(), v.name()));
        Map<UUID, String> owners = people.names(list.stream().map(Contract::ownerId).toList());
        List<UUID> ids = list.stream().map(Contract::getId).toList();
        Map<UUID, Long> assetCounts = search.countByContract(ids);
        Map<UUID, Long> licenseCounts = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("select contract_id, count(*) from software_license where contract_id in (:ids) group by contract_id")
                    .param("ids", ids)
                    .query(rs -> {
                        licenseCounts.put(rs.getObject(1, UUID.class), rs.getLong(2));
                    });
        }
        return list.stream().map(c -> new ContractView(c.getId(), c.number(), c.title(), c.kind(),
                c.vendorId() == null ? null : new Named(c.vendorId(), vendorNames.get(c.vendorId())), c.startsOn(),
                c.endsOn(), c.endsOn() == null ? null : ChronoUnit.DAYS.between(today, c.endsOn()),
                c.renewalNoticeDays(), c.autoRenew(), c.amount(), c.currency(),
                AssetService.named(c.ownerId(), owners), c.notes(), c.terminated(), c.status(today), c.remindedFor(),
                assetCounts.getOrDefault(c.getId(), 0L), licenseCounts.getOrDefault(c.getId(), 0L), c.getVersion()))
                .toList();
    }

    // ------------------------------------------------------------------ поставщики

    @Transactional(readOnly = true)
    public List<AssetDtos.VendorView> vendors() {
        return vendors.findAllOrdered().stream().map(Vendor::toView).toList();
    }

    @Transactional
    public AssetDtos.VendorView createVendor(AssetDtos.VendorFields f) {
        if (f.name() == null || f.name().isBlank()) {
            throw ApiException.badRequest("required", "Нужно название поставщика");
        }
        var v = new Vendor(f.name().strip());
        v.apply(f);
        return saveVendor(v).toView();
    }

    @Transactional
    public AssetDtos.VendorView updateVendor(UUID id, String ifMatch, AssetDtos.VendorFields f) {
        Vendor v = vendors.findById(id).orElseThrow(() -> ApiException.notFound("Поставщик"));
        ETags.requireMatch(ifMatch, v.getVersion());
        v.apply(f);
        return saveVendor(v).toView();
    }

    private Vendor saveVendor(Vendor v) {
        try {
            return vendors.save(v);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_name", "Поставщик с таким названием уже есть");
        }
    }

    // ------------------------------------------------------------------ местоположения

    @Transactional(readOnly = true)
    public List<AssetDtos.LocationView> locations() {
        return locations.findAllOrdered().stream().map(Location::toView).toList();
    }

    @Transactional
    public AssetDtos.LocationView createLocation(AssetDtos.LocationFields f) {
        if (f.name() == null || f.name().isBlank()) {
            throw ApiException.badRequest("required", "Нужно название местоположения");
        }
        var l = new Location(f.name().strip(), AssetDtos.blankToNull(f.address()));
        if (f.active() != null) {
            l.update(null, null, f.active());
        }
        return saveLocation(l).toView();
    }

    @Transactional
    public AssetDtos.LocationView updateLocation(UUID id, String ifMatch, AssetDtos.LocationFields f) {
        Location l = locations.findById(id).orElseThrow(() -> ApiException.notFound("Местоположение"));
        ETags.requireMatch(ifMatch, l.getVersion());
        l.update(f.name() == null ? null : f.name().strip(), f.address(), f.active());
        return saveLocation(l).toView();
    }

    private Location saveLocation(Location l) {
        try {
            return locations.save(l);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("duplicate_name", "Местоположение с таким названием уже есть");
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
