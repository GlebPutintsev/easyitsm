package ru.easyitsm.assets.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.assets.api.AssetState;
import ru.easyitsm.assets.events.LicenseExpiring;
import ru.easyitsm.assets.internal.AssetDtos.LicenseView;
import ru.easyitsm.assets.internal.AssetDtos.Named;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/** Лицензии ПО (AST-03): права, использование и сроки. */
@Service
class SoftwareLicenseService {

    private final LicenseRepository licenses;
    private final LicenseAssignments assignments;
    private final AssetRepository assets;
    private final VendorRepository vendors;
    private final ContractRepository contracts;
    private final People people;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SoftwareLicenseService(LicenseRepository licenses, LicenseAssignments assignments, AssetRepository assets,
                   VendorRepository vendors, ContractRepository contracts, People people, JdbcClient jdbc,
                   Notifier notifier, ApplicationEventPublisher events, Optional<Clock> clock) {
        this.licenses = licenses;
        this.assignments = assignments;
        this.assets = assets;
        this.vendors = vendors;
        this.contracts = contracts;
        this.people = people;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.events = events;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * Все лицензии арендатора. Их сотни, а не миллионы, поэтому список без страниц: фильтр по строке и
     * состоянию на сервере, сортировка по продукту.
     */
    @Transactional(readOnly = true)
    public List<LicenseView> list(String q, String status) {
        List<SoftwareLicense> all = licenses.findAll();
        Map<UUID, Long> used = assignments.usage(all.stream().map(SoftwareLicense::getId).toList());
        Views v = views(all);
        LocalDate today = today();
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase();
        return all.stream()
                .filter(l -> needle == null || l.product().toLowerCase().contains(needle)
                        || (l.publisher() != null && l.publisher().toLowerCase().contains(needle)))
                .map(l -> view(l, used.getOrDefault(l.getId(), 0L), v, today))
                .filter(l -> status == null || status.equals(l.status())
                        || ("overused".equals(status) && l.overused()))
                .sorted(Comparator.comparing((LicenseView l) -> l.product().toLowerCase()).thenComparing(LicenseView::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public LicenseView get(UUID id) {
        SoftwareLicense l = find(id);
        return view(l, assignments.used(id), views(List.of(l)), today());
    }

    @Transactional
    public LicenseView create(AssetDtos.LicenseFields f) {
        if (f.product() == null || f.product().isBlank() || f.metric() == null) {
            throw ApiException.badRequest("required", "Нужны продукт и метрика");
        }
        var l = new SoftwareLicense(f.product().strip(), f.metric());
        check(f);
        l.apply(f);
        l = licenses.save(l);
        return get(l.getId());
    }

    @Transactional
    public LicenseView update(UUID id, String ifMatch, AssetDtos.LicenseFields f) {
        SoftwareLicense l = find(id);
        ETags.requireMatch(ifMatch, l.getVersion());
        check(f);
        l.apply(f);
        l = licenses.save(l);
        return get(l.getId());
    }

    private void check(AssetDtos.LicenseFields f) {
        if (f.vendorId() != null && vendors.findById(f.vendorId()).isEmpty()) {
            throw ApiException.badRequest("unknown_vendor", "Поставщик не найден");
        }
        if (f.contractId() != null && contracts.findById(f.contractId()).isEmpty()) {
            throw ApiException.badRequest("unknown_contract", "Договор не найден");
        }
        people.requireKnown(f.ownerId(), "ownerId");
    }

    // ------------------------------------------------------------------ использование

    @Transactional(readOnly = true)
    public List<AssetDtos.AssignmentView> assignments(UUID id) {
        SoftwareLicense l = find(id);
        return assignmentViews(assignments.ofLicense(id), Map.of(id, l.product()));
    }

    /** Лицензии, занятые устройством: вкладка «ПО» в карточке актива. */
    @Transactional(readOnly = true)
    public List<AssetDtos.AssignmentView> ofAsset(UUID assetId) {
        if (assets.findById(assetId).isEmpty()) {
            throw ApiException.notFound("Актив");
        }
        var rows = assignments.ofAsset(assetId);
        return assignmentViews(rows, products(rows));
    }

    /** Лицензии, закреплённые за сотрудником. */
    @Transactional(readOnly = true)
    public List<AssetDtos.AssignmentView> ofUser(UUID userId) {
        var rows = assignments.ofUser(userId);
        return assignmentViews(rows, products(rows));
    }

    @Transactional
    public AssetDtos.AssignmentView assign(UUID id, AssetDtos.Assignment cmd) {
        if ((cmd.assetId() == null) == (cmd.userId() == null)) {
            throw ApiException.badRequest("invalid_assignment", "Укажите устройство или сотрудника");
        }
        // Блокировка строки лицензии: два одновременных назначения не превысят количество.
        jdbc.sql("select id from software_license where id = :id for update").param("id", id).query(UUID.class)
                .optional().orElseThrow(() -> ApiException.notFound("Лицензия"));
        SoftwareLicense l = find(id);
        if (cmd.assetId() != null) {
            Asset a = assets.findById(cmd.assetId())
                    .orElseThrow(() -> ApiException.badRequest("unknown_asset", "Актив не найден"));
            if (a.state() == AssetState.RETIRED) {
                throw ApiException.conflict("invalid_state", "Списанному активу лицензию не назначить");
            }
        } else {
            people.requireActive(cmd.userId(), "userId");
        }
        if (l.enforcesQuantity() && assignments.used(id) >= l.quantity()) {
            throw ApiException.conflict("license_exhausted", "Все лицензии заняты: освободите одну или увеличьте количество");
        }
        UUID created = assignments.add(id, cmd.assetId(), cmd.userId(), clock.instant());
        if (created == null) {
            throw ApiException.conflict("already_assigned", "Лицензия уже назначена");
        }
        return assignmentViews(assignments.ofLicense(id), Map.of(id, l.product())).stream()
                .filter(v -> v.id().equals(created)).findFirst().orElseThrow();
    }

    @Transactional
    public void unassign(UUID id, UUID assignmentId) {
        find(id);
        if (!assignments.remove(id, assignmentId)) {
            throw ApiException.notFound("Назначение");
        }
    }

    // ------------------------------------------------------------------ напоминания (AST-03)

    /** Наступил срок напоминания: событие и уведомление ответственному. Вызывается под арендатором лицензии. */
    @Transactional
    public void remindBySchedule(UUID id, LocalDate today) {
        licenses.findById(id).filter(l -> l.validTo() != null).ifPresent(l -> {
            l.markReminded();
            licenses.save(l);
            int days = (int) ChronoUnit.DAYS.between(today, l.validTo());
            events.publishEvent(new LicenseExpiring(l.getTenantId(), l.getId(), l.product(), l.validTo(), days,
                    l.ownerId()));
            if (l.ownerId() != null) {
                notifier.notify(l.ownerId(), "assets", "Заканчивается лицензия: " + l.product(),
                        "Действует до " + l.validTo() + " (осталось дней: " + days + ")",
                        "/agent/assets/licenses/" + l.getId(), true);
            }
        });
    }

    // ------------------------------------------------------------------ служебное

    private SoftwareLicense find(UUID id) {
        return licenses.findById(id).orElseThrow(() -> ApiException.notFound("Лицензия"));
    }

    private Map<UUID, String> products(List<LicenseAssignments.Row> rows) {
        Map<UUID, String> out = new java.util.HashMap<>();
        licenses.findAllById(rows.stream().map(LicenseAssignments.Row::licenseId).distinct().toList())
                .forEach(l -> out.put(l.getId(), l.product()));
        return out;
    }

    private List<AssetDtos.AssignmentView> assignmentViews(List<LicenseAssignments.Row> rows,
                                                           Map<UUID, String> products) {
        Set<UUID> users = new HashSet<>();
        rows.forEach(r -> {
            users.add(r.userId());
            users.add(r.assignedBy());
        });
        users.remove(null);
        Map<UUID, String> names = people.names(users);
        return rows.stream().map(r -> new AssetDtos.AssignmentView(r.id(), r.licenseId(), products.get(r.licenseId()),
                r.assetId() == null ? null : new Named(r.assetId(), r.assetTag() + " · " + r.assetName()),
                AssetService.named(r.userId(), names), AssetService.named(r.assignedBy(), names), r.assignedAt()))
                .toList();
    }

    private record Views(Map<UUID, String> vendors, Map<UUID, AssetDtos.ContractRef> contracts,
                         Map<UUID, String> owners) {
    }

    private Views views(List<SoftwareLicense> list) {
        Map<UUID, String> v = new java.util.HashMap<>();
        vendors.findAllById(list.stream().map(SoftwareLicense::vendorId).filter(java.util.Objects::nonNull)
                .distinct().toList()).forEach(x -> v.put(x.getId(), x.name()));
        Map<UUID, AssetDtos.ContractRef> c = new java.util.HashMap<>();
        contracts.findAllById(list.stream().map(SoftwareLicense::contractId).filter(java.util.Objects::nonNull)
                .distinct().toList()).forEach(x -> c.put(x.getId(),
                new AssetDtos.ContractRef(x.getId(), x.number(), x.title())));
        Map<UUID, String> o = people.names(list.stream().map(SoftwareLicense::ownerId).toList());
        return new Views(v, c, o);
    }

    private static LicenseView view(SoftwareLicense l, long used, Views v, LocalDate today) {
        Long available = l.quantity() == null ? null : l.quantity() - used;
        Long daysLeft = l.validTo() == null ? null : ChronoUnit.DAYS.between(today, l.validTo());
        return new LicenseView(l.getId(), l.product(), l.publisher(), l.metric(), l.quantity(), used, available,
                l.quantity() != null && used > l.quantity(), l.validFrom(), l.validTo(), daysLeft,
                l.renewalNoticeDays(), l.status(today),
                l.vendorId() == null ? null : new Named(l.vendorId(), v.vendors().get(l.vendorId())),
                l.contractId() == null ? null : v.contracts().get(l.contractId()), l.cost(), l.currency(),
                AssetService.named(l.ownerId(), v.owners()), l.notes(), l.getVersion());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
