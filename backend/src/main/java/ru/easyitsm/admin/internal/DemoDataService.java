package ru.easyitsm.admin.internal;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.DirectoryAdmin;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.sla.api.SlaSetup;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketType;

/**
 * «Загрузить демоданные» (ADM-01): подразделения, группы поддержки, заявители, сроки SLA по умолчанию и несколько
 * заявок, чтобы очередь и портал не были пустыми. Всё создаётся через API модулей; что уже есть по имени,
 * не дублируется. Загрузить можно один раз.
 */
@Service
class DemoDataService {

    record DemoUser(String login, String name, String department, String group) {
    }

    record DemoTicket(TicketType type, String title, String description, Level impact, Level urgency, String group,
                      String requester) {
    }

    static final List<String> DEPARTMENTS = List.of("Бухгалтерия", "Отдел продаж", "ИТ-отдел");
    static final Map<String, String> GROUPS = new LinkedHashMap<>(Map.of(
            "Service Desk", "support",
            "Сеть и связь", "support",
            "1С и учётные системы", "support",
            "Информационная безопасность", "security"));
    static final List<DemoUser> USERS = List.of(
            new DemoUser("demo.smirnova", "Анна Смирнова", "Бухгалтерия", null),
            new DemoUser("demo.ivanov", "Пётр Иванов", "Отдел продаж", null),
            new DemoUser("demo.kuznetsova", "Ольга Кузнецова", "ИТ-отдел", "Service Desk"),
            new DemoUser("demo.sokolov", "Дмитрий Соколов", "ИТ-отдел", "Сеть и связь"));
    static final List<DemoTicket> TICKETS = List.of(
            new DemoTicket(TicketType.INCIDENT, "Не печатает принтер на 3 этаже",
                    "Принтер HP в переговорной показывает ошибку замятия, бумаги внутри нет.", Level.MEDIUM,
                    Level.MEDIUM, "Service Desk", "demo.smirnova"),
            new DemoTicket(TicketType.INCIDENT, "Нет доступа к VPN из дома",
                    "С утра клиент VPN пишет «Сертификат недействителен».", Level.LOW, Level.HIGH, "Сеть и связь",
                    "demo.ivanov"),
            new DemoTicket(TicketType.INCIDENT, "1С не проводит документы реализации",
                    "При проведении ошибка блокировки таблицы, затронут весь отдел продаж.", Level.HIGH,
                    Level.HIGH, "1С и учётные системы", "demo.ivanov"),
            new DemoTicket(TicketType.REQUEST, "Доступ к общей папке «Договоры»",
                    "Прошу выдать доступ на чтение для новой сотрудницы.", Level.LOW, Level.LOW, "Service Desk",
                    "demo.smirnova"),
            new DemoTicket(TicketType.REQUEST, "Новый ноутбук для сотрудника",
                    "Сотрудник выходит в понедельник, нужен ноутбук со стандартным набором программ.", Level.LOW,
                    Level.MEDIUM, "Service Desk", "demo.ivanov"));

    private final SetupService setup;
    private final OrgUnitService orgUnits;
    private final DirectoryAdmin directory;
    private final SlaSetup sla;
    private final TicketCommands tickets;
    private final ConfigVersionService versions;

    DemoDataService(SetupService setup, OrgUnitService orgUnits, DirectoryAdmin directory, SlaSetup sla,
                    TicketCommands tickets, ConfigVersionService versions) {
        this.setup = setup;
        this.orgUnits = orgUnits;
        this.directory = directory;
        this.sla = sla;
        this.tickets = tickets;
        this.versions = versions;
    }

    record Loaded(int departments, int groups, int users, boolean slaInstalled, List<String> tickets) {
    }

    @Transactional
    Loaded load() {
        SetupService.State state = setup.get();
        setup.markDemoLoaded();

        List<UUID> createdUnits = new ArrayList<>();
        Map<String, UUID> departments = new LinkedHashMap<>();
        for (String d : DEPARTMENTS) {
            departments.put(d, orgUnits.findOrCreateDepartment(d, createdUnits).getId());
        }

        int groupsCreated = 0;
        Map<String, UUID> groups = new LinkedHashMap<>();
        for (var g : GROUPS.entrySet()) {
            var existing = directory.findGroupByName(g.getKey());
            if (existing.isPresent()) {
                groups.put(g.getKey(), existing.get());
            } else {
                groups.put(g.getKey(), directory.createGroup(g.getKey(), g.getValue(), "Демоданные"));
                groupsCreated++;
            }
        }

        int usersCreated = 0;
        Map<String, UUID> users = new LinkedHashMap<>();
        String tz = state.organization().timezone();
        for (DemoUser u : USERS) {
            var existing = directory.findUserByLogin(u.login());
            UUID id = existing.orElseGet(() -> directory.createUser(new DirectoryAdmin.NewUser(u.login(), null,
                    u.name(), state.organization().locale(), tz, null, departments.get(u.department()))));
            if (existing.isEmpty()) {
                usersCreated++;
                if (u.group() != null) {
                    directory.addMember(groups.get(u.group()), id, false);
                }
            }
            users.put(u.login(), id);
        }

        boolean slaInstalled = false;
        if (!"done".equals(state.steps().get("sla"))) {
            sla.installDefaults(ZoneId.of(tz));
            setup.setStep("sla", "done");
            slaInstalled = true;
        }

        UUID actor = CurrentActor.userId().orElse(null);
        List<String> numbers = new ArrayList<>();
        for (DemoTicket t : TICKETS) {
            var ticket = new NewTicket(t.type(), t.title(), t.description(), users.get(t.requester()), null, null,
                    Channel.PORTAL, t.impact(), t.urgency(), null, null, null, null, groups.get(t.group()), null,
                    Map.of(), false, actor);
            numbers.add(tickets.create(ticket).number());
        }

        var loaded = new Loaded(createdUnits.size(), groupsCreated, usersCreated, slaInstalled, numbers);
        versions.record("admin.setup", "demo", "Демоданные", "setup", loaded, "Загружены демоданные");
        return loaded;
    }
}
