package ru.easyitsm.mim.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.announcements.api.AnnouncementQueries;
import ru.easyitsm.catalog.api.CatalogItemRef;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.mim.api.MajorIncidentQueries;
import ru.easyitsm.mim.events.MajorIncidentClosed;
import ru.easyitsm.mim.events.MajorIncidentDeclared;
import ru.easyitsm.mim.events.MajorIncidentUpdatePublished;
import ru.easyitsm.mim.internal.MimDtos.EventView;
import ru.easyitsm.mim.internal.MimDtos.MajorIncidentRow;
import ru.easyitsm.mim.internal.MimDtos.MajorIncidentView;
import ru.easyitsm.mim.internal.MimDtos.PageView;
import ru.easyitsm.mim.internal.MimDtos.Permissions;
import ru.easyitsm.mim.internal.MimDtos.Rendered;
import ru.easyitsm.mim.internal.MimDtos.ServiceRef;
import ru.easyitsm.mim.internal.MimDtos.StatusAnnouncement;
import ru.easyitsm.mim.internal.MimDtos.StatusIncident;
import ru.easyitsm.mim.internal.MimDtos.StatusPage;
import ru.easyitsm.mim.internal.MimDtos.Step;
import ru.easyitsm.mim.internal.MimDtos.TaskView;
import ru.easyitsm.mim.internal.MimDtos.TicketRef;
import ru.easyitsm.mim.internal.MimDtos.UpdateView;
import ru.easyitsm.mim.internal.MimStore.Incident;
import ru.easyitsm.mim.internal.MimStore.Page;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.problems.api.NewProblem;
import ru.easyitsm.problems.api.ProblemCommands;
import ru.easyitsm.problems.api.ProblemRef;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketLinks;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Командный центр крупных инцидентов (MIM-01…05). Крупный инцидент — надстройка над заявкой-инцидентом: заявка
 * остаётся в модуле tickets (приоритет, лента, SLA), здесь — роли, хронология, задачи, сообщения, оповещения и разбор.
 *
 * <p>Читать командный центр может тот, кто видит заявку, участник (роль, задача, оповещение), {@code MIM} и
 * {@code AUD}. Вести — {@code MIM}, {@code LEAD} и держатель роли из поддержки.
 */
@Service
class MimService implements MajorIncidentQueries {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    private static final Set<String> AUDIENCES = Set.of("users", "management");
    private static final Set<String> CHANNELS = Set.of("center", "email");
    private static final Map<String, List<String>> FILTERS = Map.of(
            "open", List.of("requested", "active", "restored"),
            "requested", List.of("requested"),
            "active", List.of("active", "restored"),
            "closed", List.of("closed", "rejected"),
            "all", List.of("requested", "active", "restored", "closed", "rejected"));
    private static final Map<String, String> STATE_NAMES = Map.of(
            "requested", "на согласовании", "active", "идёт восстановление", "restored", "сервис восстановлен",
            "closed", "закрыт", "rejected", "отклонён");
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "manager", "Менеджер", "tech_lead", "Технический лидер", "comms", "Коммуникации", "scribe", "Хронист");

    private final MimStore store;
    private final MessageTemplates templates;
    private final Paging paging;
    private final TicketQueries tickets;
    private final TicketCommands commands;
    private final TicketLinks links;
    private final UserDirectory directory;
    private final CatalogQueries catalog;
    private final AnnouncementQueries announcements;
    private final ProblemCommands problems;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;

    MimService(MimStore store, MessageTemplates templates, Paging paging, TicketQueries tickets,
               TicketCommands commands, TicketLinks links, UserDirectory directory, CatalogQueries catalog,
               AnnouncementQueries announcements, ProblemCommands problems, Notifier notifier,
               ApplicationEventPublisher events) {
        this.store = store;
        this.templates = templates;
        this.paging = paging;
        this.tickets = tickets;
        this.commands = commands;
        this.links = links;
        this.directory = directory;
        this.catalog = catalog;
        this.announcements = announcements;
        this.problems = problems;
        this.notifier = notifier;
        this.events = events;
    }

    // ================================================================ объявление (MIM-01)

    @Transactional
    public MajorIncidentView declare(MimDtos.Declare in) {
        UUID me = Me.id();
        Me.requireAny(Me.SUPPORT);
        TicketSnapshot t = tickets.findVisible(in.ticketId()).orElseThrow(() -> ApiException.notFound("Заявка"));
        if (t.type() != TicketType.INCIDENT) {
            throw ApiException.badRequest("not_incident", "Крупным объявляется только инцидент");
        }
        if (t.statusCategory() == StatusCategory.CLOSED || t.statusCategory() == StatusCategory.CANCELLED) {
            throw ApiException.conflict("ticket_closed", "Инцидент закрыт");
        }
        if (store.openForTicket(t.id()).isPresent()) {
            throw ApiException.conflict("already_major", "Инцидент уже объявлен крупным");
        }
        boolean direct = Me.hasAny(Me.DECLARERS);
        UUID manager = in.managerId() != null ? in.managerId() : (direct ? me : null);
        if (manager != null) {
            requireManagerRole(manager);
        }
        List<UUID> services = in.serviceIds() != null && !in.serviceIds().isEmpty()
                ? List.copyOf(new LinkedHashSet<>(in.serviceIds()))
                : t.serviceId() != null ? List.of(t.serviceId()) : List.of();
        String title = blank(in.publicTitle()) ? t.title() : in.publicTitle().strip();
        UUID id = store.insert(t.id(), direct ? "active" : "requested", title, in.reason().strip(), services, me,
                direct);
        if (manager != null) {
            store.setRole(id, "manager", manager, me);
        }
        store.event(id, "declare_requested", null, (direct ? "Объявлен крупным: " : "Запрошено объявление крупным: ")
                + in.reason().strip(), me, Map.of());
        Incident incident = store.find(id, false).orElseThrow();
        if (direct) {
            activate(incident, t, manager, me);
        } else {
            commands.addComment(t.id(), me, "Запрошено объявление крупным инцидентом. Обоснование: "
                    + in.reason().strip(), Visibility.INTERNAL);
            if (t.groupId() != null) {
                directory.leaderOf(t.groupId()).filter(l -> !l.equals(me)).ifPresent(l -> notifier.notify(l,
                        "major_incident", "Согласуйте крупный инцидент " + t.number(), title, Paging.link(id),
                        false));
            }
        }
        return view(id);
    }

    @Transactional
    public MajorIncidentView approve(UUID id) {
        UUID me = Me.id();
        Me.requireAny(Me.DECLARERS);
        Incident i = readable(id, true);
        if (!"requested".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Объявление уже рассмотрено");
        }
        UUID manager = store.roles(id).get("manager");
        if (manager == null) {
            manager = me;
            store.setRole(id, "manager", me, me);
        }
        Instant now = Db.now();
        store.update(copy(i, "active", i.publicTitle(), i.hypothesis(), i.serviceIds(), me, now, null, null,
                i.nextUpdateAt(), i.pir(), i.problemId()), me);
        store.event(id, "approved", now, "Объявление согласовано", me, Map.of());
        TicketSnapshot t = tickets.find(i.ticketId()).orElseThrow();
        activate(store.find(id, false).orElseThrow(), t, manager, me);
        return view(id);
    }

    @Transactional
    public MajorIncidentView reject(UUID id, MimDtos.Reject in) {
        UUID me = Me.id();
        Me.requireAny(Me.DECLARERS);
        Incident i = readable(id, true);
        if (!"requested".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Объявление уже рассмотрено");
        }
        Instant now = Db.now();
        store.update(copy(i, "rejected", i.publicTitle(), i.hypothesis(), i.serviceIds(), i.approvedBy(),
                i.approvedAt(), null, now, null, i.pir(), i.problemId()), me);
        store.event(id, "rejected", now, "Объявление отклонено: " + in.reason().strip(), me, Map.of());
        commands.addComment(i.ticketId(), me, "Объявление крупным инцидентом отклонено: " + in.reason().strip(),
                Visibility.INTERNAL);
        if (i.declaredBy() != null && !i.declaredBy().equals(me)) {
            notifier.notify(i.declaredBy(), "major_incident", "Объявление крупным отклонено",
                    in.reason().strip(), "/agent/tickets/" + number(i.ticketId()), false);
        }
        return view(id);
    }

    /** Заявка становится P1, в ленте — заметка, менеджеру — уведомление, наружу — событие для оповещений. */
    private void activate(Incident i, TicketSnapshot t, UUID manager, UUID actor) {
        if (t.priority() != Priority.P1) {
            commands.change(t.id(), new TicketChange(Priority.P1, null, null, null, null, false,
                    "Объявлен крупным инцидентом"));
        }
        commands.addComment(t.id(), actor, "Объявлен крупный инцидент. Обоснование: " + i.reason()
                + "\n\nКомандный центр: " + Paging.link(i.id()), Visibility.INTERNAL);
        if (manager != null) {
            commands.addWatcher(t.id(), manager, "major_incident");
            if (!manager.equals(actor)) {
                notifier.notify(manager, "major_incident", "Вы менеджер крупного инцидента " + t.number(),
                        i.publicTitle(), Paging.link(i.id()), true);
            }
        }
        events.publishEvent(new MajorIncidentDeclared(TenantContext.require(), Db.now(), i.id(), t.id(), t.number(),
                i.publicTitle(), i.serviceIds(), manager, actor));
    }

    // ================================================================ командный центр (MIM-02)

    @Transactional
    public MajorIncidentView get(UUID id) {
        readable(id, false);
        return view(id);
    }

    /** Последний крупный инцидент заявки; нет — 404. */
    @Transactional
    public MajorIncidentView byTicket(UUID ticketId) {
        Incident i = store.latestForTicket(ticketId)
                .filter(this::canRead).orElseThrow(() -> ApiException.notFound("Крупный инцидент"));
        return view(i.id());
    }

    @Transactional(readOnly = true)
    public List<MajorIncidentRow> list(String filter, int limit) {
        List<String> states = FILTERS.get(filter == null ? "open" : filter);
        if (states == null) {
            throw ApiException.badRequest("invalid_filter", "Фильтр: open, requested, active, closed, all");
        }
        Map<UUID, String> serviceNames = serviceNames();
        List<MajorIncidentRow> out = new ArrayList<>();
        for (Incident i : store.list(states, Math.clamp(limit, 1, 200))) {
            if (!canRead(i)) {
                continue;
            }
            int children = (int) links.links(i.ticketId()).stream().filter(l -> "parent_of".equals(l.kind()))
                    .count();
            out.add(new MajorIncidentRow(i.id(), ticketRef(i.ticketId()), i.state(), i.publicTitle(),
                    services(i.serviceIds(), serviceNames), store.roles(i.id()).get("manager"), i.declaredAt(),
                    i.restoredAt(), i.closedAt(), i.nextUpdateAt(), children));
        }
        return out;
    }

    @Transactional
    public MajorIncidentView patch(UUID id, MimDtos.Patch in, String ifMatch) {
        UUID me = Me.id();
        Incident i = manageable(id);
        ETags.requireMatch(ifMatch, i.version());
        if ("closed".equals(i.state()) || "rejected".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Крупный инцидент закрыт");
        }
        Instant now = Db.now();
        String hypothesis = i.hypothesis();
        if (in.hypothesis() != null && !in.hypothesis().strip().equals(nz(i.hypothesis()))) {
            hypothesis = in.hypothesis().strip();
            store.event(id, "hypothesis", now, hypothesis.isEmpty() ? "Гипотеза снята" : "Гипотеза: " + hypothesis,
                    me, Map.of());
        }
        List<UUID> services = i.serviceIds();
        if (in.serviceIds() != null && !Set.copyOf(in.serviceIds()).equals(Set.copyOf(i.serviceIds()))) {
            services = List.copyOf(new LinkedHashSet<>(in.serviceIds()));
            Map<UUID, String> names = serviceNames();
            store.event(id, "services", now, "Затронутые услуги: " + (services.isEmpty() ? "не указаны"
                    : services(services, names).stream().map(ServiceRef::name).collect(Collectors.joining(", "))),
                    me, Map.of("serviceIds", services.stream().map(UUID::toString).toList()));
        }
        String title = in.publicTitle() != null && !in.publicTitle().isBlank() ? in.publicTitle().strip()
                : i.publicTitle();
        Instant next = in.nextUpdateAt() != null ? in.nextUpdateAt() : i.nextUpdateAt();
        store.update(copy(i, i.state(), title, hypothesis, services, i.approvedBy(), i.approvedAt(), i.restoredAt(),
                i.closedAt(), next, i.pir(), i.problemId()), me);
        return view(id);
    }

    @Transactional
    public MajorIncidentView assignRole(UUID id, String role, UUID userId) {
        UUID me = Me.id();
        Incident i = manageable(id);
        requireOpen(i);
        if (!MimDtos.ROLES.contains(role)) {
            throw ApiException.badRequest("invalid_role", "Роль: " + String.join(", ", MimDtos.ROLES));
        }
        if (userId != null) {
            UserSummary u = directory.findUser(userId).filter(UserSummary::active)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_user",
                            "Пользователь не найден"));
            if ("manager".equals(role)) {
                requireManagerRole(userId);
            } else if (directory.rolesOf(userId).stream().noneMatch(r -> Me.SUPPORT.contains(r.name()))) {
                throw ApiException.badRequest("invalid_user", "Роль назначается сотруднику поддержки");
            }
            store.setRole(id, role, userId, me);
            store.event(id, "role", null, ROLE_NAMES.get(role) + ": " + u.displayName(), me,
                    Map.of("role", role, "userId", userId.toString()));
            commands.addWatcher(i.ticketId(), userId, "major_incident");
            if (!userId.equals(me)) {
                notifier.notify(userId, "major_incident", ROLE_NAMES.get(role) + " в крупном инциденте "
                        + number(i.ticketId()), i.publicTitle(), Paging.link(id), true);
            }
        } else {
            if ("manager".equals(role)) {
                throw ApiException.badRequest("manager_required", "Менеджер у крупного инцидента обязателен");
            }
            store.setRole(id, role, null, me);
            store.event(id, "role", null, ROLE_NAMES.get(role) + ": снят", me, Map.of("role", role));
        }
        return view(id);
    }

    @Transactional
    public MajorIncidentView addEvent(UUID id, MimDtos.AddEvent in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        Instant now = Db.now();
        if (in.at() != null && in.at().isAfter(now)) {
            throw ApiException.badRequest("invalid_time", "Время события в будущем");
        }
        store.event(i.id(), "note", in.at(), in.text().strip(), me, Map.of());
        return view(id);
    }

    @Transactional
    public MajorIncidentView addTask(UUID id, MimDtos.AddTask in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        requireOpen(i);
        if (in.assigneeId() != null) {
            directory.findUser(in.assigneeId()).filter(UserSummary::active)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_user",
                            "Пользователь не найден"));
        }
        store.addTask(id, in.title().strip(), in.assigneeId(), me);
        store.event(id, "task", null, "Задача: " + in.title().strip(), me, Map.of());
        if (in.assigneeId() != null && !in.assigneeId().equals(me)) {
            notifier.notify(in.assigneeId(), "major_incident", "Задача в крупном инциденте " + number(i.ticketId()),
                    in.title().strip(), Paging.link(id), false);
        }
        return view(id);
    }

    @Transactional
    public MajorIncidentView setTaskDone(UUID id, UUID taskId, boolean done) {
        UUID me = Me.id();
        Incident i = readable(id, false);
        TaskView task = store.tasks(id).stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Задача"));
        if (!canManage(i) && !me.equals(task.assigneeId())) {
            throw ApiException.forbidden();
        }
        store.setTaskDone(id, taskId, done, me);
        if (done && !task.done()) {
            store.event(id, "task_done", null, "Выполнено: " + task.title(), me, Map.of());
        }
        return view(id);
    }

    /** Связать дочерние инциденты (INC-09): те же пользователи получат сообщения крупного инцидента. */
    @Transactional
    public MajorIncidentView linkChildren(UUID id, MimDtos.LinkChildren in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        requireOpen(i);
        List<String> added = new ArrayList<>();
        for (UUID child : new LinkedHashSet<>(in.incidentIds())) {
            if (child.equals(i.ticketId())) {
                continue;
            }
            TicketSnapshot c = tickets.findVisible(child).orElseThrow(() -> ApiException.notFound("Инцидент"));
            if (c.type() != TicketType.INCIDENT) {
                throw ApiException.badRequest("not_incident", c.number() + " — не инцидент");
            }
            if (links.link(i.ticketId(), child, "parent_of", me)) {
                added.add(c.number());
            }
        }
        if (!added.isEmpty()) {
            store.event(id, "children", null, "Связаны инциденты: " + String.join(", ", added), me, Map.of());
        }
        return view(id);
    }

    // ================================================================ сообщения (MIM-04)

    @Transactional
    public Rendered render(UUID id, UUID templateId) {
        Incident i = readable(id, false);
        MimStore.Template t = store.template(templateId, false)
                .orElseThrow(() -> ApiException.notFound("Шаблон"));
        Map<String, String> vars = vars(i, zone());
        return new Rendered(MessageTemplates.render(t.title(), vars), MessageTemplates.render(t.body(), vars),
                t.audience());
    }

    @Transactional
    public MajorIncidentView publish(UUID id, MimDtos.Publish in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        if (!i.open()) {
            throw ApiException.conflict("invalid_state", "Сообщения публикуются по объявленному инциденту");
        }
        if (!AUDIENCES.contains(in.audience())) {
            throw ApiException.badRequest("invalid_audience", "Аудитория: users или management");
        }
        Instant now = Db.now();
        if (in.nextUpdateAt() != null && in.nextUpdateAt().isBefore(now)) {
            throw ApiException.badRequest("invalid_time", "Время следующего сообщения уже прошло");
        }
        boolean statusPage = in.onStatusPage() && "users".equals(in.audience());
        MimDtos.Publish p = new MimDtos.Publish(in.audience(), in.title().strip(), in.body().strip(), statusPage,
                in.nextUpdateAt());
        UUID updateId = store.addUpdate(id, p, me);
        String audienceName = "users".equals(in.audience()) ? "пользователям" : "руководству";
        store.event(id, "update", now, "Сообщение " + audienceName + ": " + p.title(), me,
                Map.of("updateId", updateId.toString(), "audience", in.audience()));
        if ("users".equals(in.audience())) {
            String text = "**" + p.title() + "**\n\n" + p.body();
            commentIfOpen(i.ticketId(), me, text);
            for (UUID child : children(i.ticketId())) {
                commentIfOpen(child, me, text);
            }
        } else {
            commands.addComment(i.ticketId(), me, "Сводка руководству: **" + p.title() + "**\n\n" + p.body(),
                    Visibility.INTERNAL);
        }
        if (in.nextUpdateAt() != null) {
            store.update(copy(i, i.state(), i.publicTitle(), i.hypothesis(), i.serviceIds(), i.approvedBy(),
                    i.approvedAt(), i.restoredAt(), i.closedAt(), in.nextUpdateAt(), i.pir(), i.problemId()), me);
        }
        events.publishEvent(new MajorIncidentUpdatePublished(TenantContext.require(), now, id, updateId,
                in.audience(), p.title(), p.body(), statusPage, in.nextUpdateAt()));
        return view(id);
    }

    private void commentIfOpen(UUID ticketId, UUID actor, String text) {
        tickets.find(ticketId)
                .filter(t -> t.statusCategory() != StatusCategory.CLOSED
                        && t.statusCategory() != StatusCategory.CANCELLED)
                .ifPresent(t -> commands.addComment(t.id(), actor, text, Visibility.PUBLIC));
    }

    // ================================================================ оповещение дежурных (MIM-03)

    @Transactional
    public MajorIncidentView startPage(UUID id, MimDtos.StartPage in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        if (!i.open()) {
            throw ApiException.conflict("invalid_state", "Оповещение — по объявленному инциденту");
        }
        String channel = in.channel() == null ? "center" : in.channel();
        if (!CHANNELS.contains(channel)) {
            throw ApiException.badRequest("invalid_channel", "Канал: center или email");
        }
        String name;
        List<Step> steps;
        UUID chainId = null;
        if (in.chainId() != null) {
            MimStore.Chain chain = store.chain(in.chainId()).filter(MimStore.Chain::active)
                    .orElseThrow(() -> ApiException.notFound("Цепочка оповещения"));
            name = chain.name();
            steps = chain.steps();
            chainId = chain.id();
        } else {
            if (in.steps() == null || in.steps().isEmpty()) {
                throw ApiException.badRequest("steps_required", "Укажите цепочку или шаги оповещения");
            }
            name = "Оповещение вручную";
            steps = in.steps();
        }
        validateSteps(steps);
        UUID pageId = store.insertPage(id, chainId, name, steps, channel, me);
        paging.notifyStep(store.findPage(pageId, true).orElseThrow(), i, number(i.ticketId()), 0, me);
        return view(id);
    }

    void validateSteps(List<Step> steps) {
        for (Step s : steps) {
            if ((s.groupId() == null) == (s.userId() == null)) {
                throw ApiException.badRequest("invalid_step", "В шаге — группа или пользователь");
            }
            if (s.groupId() != null && directory.findGroup(s.groupId()).isEmpty()) {
                throw ApiException.badRequest("invalid_step", "Группа не найдена");
            }
            if (s.userId() != null && directory.findUser(s.userId()).isEmpty()) {
                throw ApiException.badRequest("invalid_step", "Пользователь не найден");
            }
        }
    }

    /** Подтверждение получения: только тот, кого оповестили. */
    @Transactional
    public MajorIncidentView acknowledge(UUID pageId) {
        UUID me = Me.id();
        Page page = store.findPage(pageId, true).orElseThrow(() -> ApiException.notFound("Оповещение"));
        Instant now = Db.now();
        if (!store.ackTarget(pageId, me, now)) {
            throw ApiException.notFound("Оповещение");
        }
        if ("paging".equals(page.state()) || "exhausted".equals(page.state())) {
            store.updatePage(pageId, page.step(), "acknowledged", null, me, now);
            String who = directory.findUser(me).map(UserSummary::displayName).orElse("—");
            store.event(page.mimId(), "acked", now, "Получение подтвердил: " + who, me,
                    Map.of("pageId", pageId.toString()));
            Incident i = store.find(page.mimId(), false).orElseThrow();
            UUID manager = store.roles(i.id()).get("manager");
            if (manager != null && !manager.equals(me)) {
                notifier.notify(manager, "major_incident", "Дежурный подтвердил оповещение",
                        who + " — " + i.publicTitle(), Paging.link(i.id()), false);
            }
            commands.addWatcher(i.ticketId(), me, "major_incident");
        }
        return view(page.mimId());
    }

    @Transactional
    public MajorIncidentView cancelPage(UUID id, UUID pageId) {
        UUID me = Me.id();
        manageable(id);
        Page page = store.findPage(pageId, true).filter(p -> p.mimId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Оповещение"));
        if ("paging".equals(page.state())) {
            store.updatePage(pageId, page.step(), "cancelled", null, null, null);
            store.event(id, "page_cancelled", null, "Оповещение «" + page.name() + "» остановлено", me, Map.of());
        }
        return view(id);
    }

    /** Задача worker: шаг без подтверждения — следующий. */
    @Transactional
    public void advancePage(UUID pageId, Instant now) {
        paging.advance(pageId, now, this::number);
    }

    // ================================================================ восстановление и закрытие (MIM-05)

    @Transactional
    public MajorIncidentView restore(UUID id, MimDtos.Restore in) {
        UUID me = Me.id();
        Incident i = manageable(id);
        if (!"active".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Восстановить можно идущий крупный инцидент");
        }
        Instant now = Db.now();
        Instant at = in.at() == null ? now : in.at();
        if (at.isAfter(now) || at.isBefore(i.declaredAt())) {
            throw ApiException.badRequest("invalid_time", "Время восстановления — между объявлением и текущим");
        }
        store.update(copy(i, "restored", i.publicTitle(), i.hypothesis(), i.serviceIds(), i.approvedBy(),
                i.approvedAt(), at, null, null, i.pir(), i.problemId()), me);
        store.event(id, "restored", at, "Сервис восстановлен" + (blank(in.comment()) ? "" : ": "
                + in.comment().strip()), me, Map.of());
        stopPaging(id);
        return view(id);
    }

    @Transactional
    public MajorIncidentView close(UUID id, MimDtos.Close in) {
        UUID me = Me.id();
        Incident i = readable(id, true);
        if (!canClose(i)) {
            throw ApiException.forbidden();
        }
        if (!"restored".equals(i.state())) {
            throw ApiException.conflict("not_restored", "Закрыть можно после восстановления сервиса");
        }
        Instant now = Db.now();
        String pir = blank(i.pir()) ? pirDraft(i, zone()) : i.pir();
        UUID problemId = i.problemId();
        String problemNumber = null;
        if (Boolean.TRUE.equals(in.createProblem()) && problemId == null) {
            TicketSnapshot t = tickets.find(i.ticketId()).orElseThrow();
            List<UUID> incidents = new ArrayList<>();
            incidents.add(t.id());
            incidents.addAll(children(t.id()));
            List<NewProblem.TimelineItem> timeline = store.events(id).stream()
                    .map(e -> new NewProblem.TimelineItem(e.at(), e.text())).toList();
            String title = blank(in.problemTitle()) ? "Причина крупного инцидента " + t.number() + ": "
                    + i.publicTitle() : in.problemTitle().strip();
            ProblemRef ref = problems.create(new NewProblem(title.length() > 255 ? title.substring(0, 255) : title,
                    "Создана при закрытии крупного инцидента " + t.number() + ".\n\n" + pir, "major_incident",
                    incidents, i.serviceIds().isEmpty() ? t.serviceId() : i.serviceIds().getFirst(), t.ciId(),
                    t.groupId(), i.hypothesis(), timeline, me));
            problemId = ref.id();
            problemNumber = ref.number();
        }
        store.update(copy(i, "closed", i.publicTitle(), i.hypothesis(), i.serviceIds(), i.approvedBy(),
                i.approvedAt(), i.restoredAt(), now, null, pir, problemId), me);
        store.event(id, "closed", now, "Крупный инцидент закрыт" + (problemNumber == null ? ""
                : ", создана проблема " + problemNumber), me, Map.of());
        stopPaging(id);
        events.publishEvent(new MajorIncidentClosed(TenantContext.require(), now, id, i.ticketId(), i.declaredAt(),
                i.restoredAt(), problemId, me));
        return view(id);
    }

    @Transactional
    public MajorIncidentView savePir(UUID id, String pir, String ifMatch) {
        UUID me = Me.id();
        Incident i = manageable(id);
        ETags.requireMatch(ifMatch, i.version());
        if ("rejected".equals(i.state()) || "requested".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Разбор — по объявленному инциденту");
        }
        store.update(copy(i, i.state(), i.publicTitle(), i.hypothesis(), i.serviceIds(), i.approvedBy(),
                i.approvedAt(), i.restoredAt(), i.closedAt(), i.nextUpdateAt(), pir, i.problemId()), me);
        return view(id);
    }

    /** Черновик разбора (PIR) по данным командного центра: сводка, хронология, роли, задачи, сообщения. */
    @Transactional(readOnly = true)
    public String pirPreview(UUID id) {
        Incident i = readable(id, false);
        return pirDraft(i, zone());
    }

    String pirDraft(Incident i, ZoneId zone) {
        String number = number(i.ticketId());
        Map<UUID, String> serviceNames = serviceNames();
        StringBuilder md = new StringBuilder();
        md.append("# Разбор крупного инцидента ").append(number).append(": ").append(i.publicTitle()).append("\n\n");
        md.append("## Сводка\n\n");
        md.append("- Объявлен: ").append(MessageTemplates.time(i.declaredAt(), zone)).append('\n');
        md.append("- Сервис восстановлен: ").append(MessageTemplates.time(i.restoredAt(), zone)).append('\n');
        md.append("- Длительность: ").append(MessageTemplates.duration(i.declaredAt(), i.restoredAt()))
                .append('\n');
        md.append("- Затронутые услуги: ").append(i.serviceIds().isEmpty() ? "не указаны"
                : services(i.serviceIds(), serviceNames).stream().map(ServiceRef::name)
                        .collect(Collectors.joining(", "))).append('\n');
        List<UUID> kids = children(i.ticketId());
        md.append("- Связанных инцидентов: ").append(kids.size()).append("\n\n");
        md.append("## Обоснование объявления\n\n").append(nz(i.reason())).append("\n\n");
        md.append("## Причина (гипотеза)\n\n").append(blank(i.hypothesis()) ? "_Не установлена._"
                : i.hypothesis()).append("\n\n");
        md.append("## Участники\n\n");
        Map<String, UUID> roles = store.roles(i.id());
        Map<UUID, String> people = names(roles.values());
        for (String r : MimDtos.ROLES) {
            UUID u = roles.get(r);
            md.append("- ").append(ROLE_NAMES.get(r)).append(": ").append(u == null ? "—" : people.getOrDefault(u,
                    "—")).append('\n');
        }
        md.append("\n## Хронология\n\n");
        for (EventView e : store.events(i.id())) {
            md.append("- ").append(MessageTemplates.time(e.at(), zone)).append(" — ").append(e.text()).append('\n');
        }
        md.append("\n## Сообщения\n\n");
        List<UpdateView> updates = new ArrayList<>(store.updates(i.id()));
        java.util.Collections.reverse(updates);
        if (updates.isEmpty()) {
            md.append("_Не публиковались._\n");
        }
        for (UpdateView u : updates) {
            md.append("- ").append(MessageTemplates.time(u.publishedAt(), zone)).append(", ")
                    .append("users".equals(u.audience()) ? "пользователям" : "руководству").append(": ")
                    .append(u.title()).append('\n');
        }
        md.append("\n## Задачи\n\n");
        List<TaskView> tasks = store.tasks(i.id());
        if (tasks.isEmpty()) {
            md.append("_Нет._\n");
        }
        for (TaskView t : tasks) {
            md.append("- [").append(t.done() ? 'x' : ' ').append("] ").append(t.title()).append('\n');
        }
        md.append("\n## Что сработало хорошо\n\n- \n\n## Что улучшить\n\n- \n\n## Действия по итогам\n\n- \n");
        return md.toString();
    }

    private void stopPaging(UUID id) {
        for (Page p : store.pages(id)) {
            if ("paging".equals(p.state())) {
                store.updatePage(p.id(), p.step(), "cancelled", null, null, null);
            }
        }
    }

    // ================================================================ страница статуса (MIM-04, 10.2)

    @Transactional(readOnly = true)
    public StatusPage status() {
        Map<UUID, String> serviceNames = serviceNames();
        List<StatusIncident> incidents = new ArrayList<>();
        for (Incident i : store.list(FILTERS.get("active"), 50)) {
            incidents.add(new StatusIncident(i.id(), i.publicTitle(), i.state(), services(i.serviceIds(),
                    serviceNames), i.declaredAt(), i.restoredAt(), i.nextUpdateAt(),
                    store.lastStatusUpdate(i.id()).map(u -> new UpdateView(u.id(), u.audience(), u.title(),
                            u.body(), true, u.nextUpdateAt(), u.publishedAt(), null)).orElse(null)));
        }
        List<StatusAnnouncement> news = announcements.activeNow().stream()
                .map(a -> new StatusAnnouncement(a.id(), a.kind(), a.title(), a.body(), a.startsAt(), a.endsAt()))
                .toList();
        return new StatusPage(incidents, news, Db.now());
    }

    // ================================================================ MajorIncidentQueries

    @Override
    @Transactional(readOnly = true)
    public List<ActiveMajorIncident> active() {
        return store.list(FILTERS.get("active"), 200).stream().map(this::active).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveMajorIncident> forTicket(UUID ticketId) {
        return store.openForTicket(ticketId).filter(Incident::open).map(this::active);
    }

    private ActiveMajorIncident active(Incident i) {
        return new ActiveMajorIncident(i.id(), i.ticketId(), number(i.ticketId()), i.publicTitle(), i.state(),
                i.serviceIds(), i.declaredAt(), i.restoredAt());
    }

    // ================================================================ представление

    MajorIncidentView view(UUID id) {
        Incident i = store.find(id, false).orElseThrow(() -> ApiException.notFound("Крупный инцидент"));
        Map<UUID, String> serviceNames = serviceNames();
        Map<String, UUID> roles = store.roles(id);
        List<MimDtos.RoleView> roleViews = MimDtos.ROLES.stream()
                .map(r -> new MimDtos.RoleView(r, roles.get(r))).toList();
        List<PageView> pages = store.pages(id).stream()
                .map(p -> new PageView(p.id(), p.name(), p.channel(), p.step(), p.steps().size(), p.state(),
                        p.nextStepAt(), p.ackedBy(), p.ackedAt(), p.createdAt(), store.targets(p.id())))
                .toList();
        List<TicketRef> kids = children(i.ticketId()).stream().map(this::ticketRef).toList();
        boolean manage = canManage(i);
        Permissions perms = new Permissions(manage && !"closed".equals(i.state()) && !"rejected".equals(i.state()),
                "requested".equals(i.state()) && Me.hasAny(Me.DECLARERS),
                "restored".equals(i.state()) && canClose(i));
        return new MajorIncidentView(i.id(), ticketRef(i.ticketId()), i.state(), i.publicTitle(), i.reason(),
                i.hypothesis(), services(i.serviceIds(), serviceNames), i.declaredBy(), i.declaredAt(),
                i.approvedBy(), i.approvedAt(), i.restoredAt(), i.closedAt(), i.nextUpdateAt(), roleViews,
                store.events(id), store.tasks(id), store.updates(id), pages, kids, i.pir(),
                i.problemId() == null ? null : ticketRef(i.problemId()), perms, i.version());
    }

    long version(UUID id) {
        return store.find(id, false).map(Incident::version).orElse(0L);
    }

    String etag(MajorIncidentView v) {
        return ETags.of(v.version());
    }

    private TicketRef ticketRef(UUID id) {
        Optional<TicketSnapshot> visible = tickets.findVisible(id);
        if (visible.isPresent()) {
            TicketSnapshot t = visible.get();
            return new TicketRef(t.id(), t.number(), t.title(), t.statusCode(), t.statusCategory().code(),
                    t.priority().code(), true);
        }
        return tickets.find(id).map(t -> new TicketRef(t.id(), t.number(), null, null, null, null, false))
                .orElse(new TicketRef(id, null, null, null, null, null, false));
    }

    private List<UUID> children(UUID ticketId) {
        return links.links(ticketId).stream().filter(l -> "parent_of".equals(l.kind()))
                .map(TicketLinks.TicketLinkRef::otherId).distinct().toList();
    }

    private Map<UUID, String> serviceNames() {
        Map<UUID, String> out = new HashMap<>();
        for (CatalogItemRef c : catalog.activeItems()) {
            out.put(c.id(), c.name());
        }
        return out;
    }

    private static List<ServiceRef> services(List<UUID> ids, Map<UUID, String> names) {
        return ids.stream().map(id -> new ServiceRef(id, names.getOrDefault(id, "Услуга"))).toList();
    }

    private Map<UUID, String> names(java.util.Collection<UUID> ids) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (UserSummary u : directory.findUsers(Set.copyOf(ids))) {
            out.put(u.id(), u.displayName());
        }
        return out;
    }

    private Map<String, String> vars(Incident i, ZoneId zone) {
        Map<String, String> v = new HashMap<>();
        v.put("number", number(i.ticketId()));
        v.put("title", i.publicTitle());
        List<ServiceRef> services = services(i.serviceIds(), serviceNames());
        v.put("services", services.isEmpty() ? "несколько услуг"
                : services.stream().map(ServiceRef::name).collect(Collectors.joining(", ")));
        v.put("status", STATE_NAMES.getOrDefault(i.state(), i.state()));
        v.put("hypothesis", blank(i.hypothesis()) ? "устанавливается" : i.hypothesis());
        v.put("nextUpdate", i.nextUpdateAt() == null ? "по мере изменений" : MessageTemplates.time(i.nextUpdateAt(),
                zone));
        v.put("startedAt", MessageTemplates.time(i.declaredAt(), zone));
        v.put("duration", MessageTemplates.duration(i.declaredAt(), i.restoredAt()));
        return v;
    }

    private ZoneId zone() {
        try {
            return directory.findUser(Me.id()).map(UserSummary::timezone).filter(z -> !blank(z)).map(ZoneId::of)
                    .orElse(DEFAULT_ZONE);
        } catch (RuntimeException e) {
            return DEFAULT_ZONE;
        }
    }

    String number(UUID ticketId) {
        return tickets.find(ticketId).map(TicketSnapshot::number).orElse("—");
    }

    // ================================================================ права

    private Incident readable(UUID id, boolean lock) {
        Incident i = store.find(id, lock).orElseThrow(() -> ApiException.notFound("Крупный инцидент"));
        if (!canRead(i)) {
            throw ApiException.notFound("Крупный инцидент");
        }
        return i;
    }

    private Incident manageable(UUID id) {
        Incident i = readable(id, true);
        if (!canManage(i)) {
            throw ApiException.forbidden();
        }
        return i;
    }

    private boolean canRead(Incident i) {
        if (Me.hasAny("MIM", "AUD")) {
            return true;
        }
        UUID me = Me.id();
        return tickets.findVisible(i.ticketId()).isPresent() || store.roles(i.id()).containsValue(me)
                || store.isPaged(i.id(), me) || store.tasks(i.id()).stream().anyMatch(t -> me.equals(t.assigneeId()));
    }

    private boolean canManage(Incident i) {
        if (Me.hasAny("MIM", "LEAD")) {
            return true;
        }
        return Me.hasAny(Me.SUPPORT) && store.roles(i.id()).containsValue(Me.id());
    }

    private boolean canClose(Incident i) {
        return Me.hasAny("MIM", "LEAD") || Me.id().equals(store.roles(i.id()).get("manager"));
    }

    private static void requireOpen(Incident i) {
        if ("closed".equals(i.state()) || "rejected".equals(i.state())) {
            throw ApiException.conflict("invalid_state", "Крупный инцидент закрыт");
        }
    }

    private void requireManagerRole(UUID userId) {
        Set<Role> roles = directory.rolesOf(userId);
        boolean active = directory.findUser(userId).map(UserSummary::active).orElse(false);
        if (!active || !(roles.contains(Role.MIM) || roles.contains(Role.LEAD))) {
            throw ApiException.badRequest("invalid_manager", "Менеджер — пользователь с ролью MIM или LEAD");
        }
    }

    private static Incident copy(Incident i, String state, String title, String hypothesis, List<UUID> services,
                                 UUID approvedBy, Instant approvedAt, Instant restoredAt, Instant closedAt,
                                 Instant nextUpdateAt, String pir, UUID problemId) {
        return new Incident(i.id(), i.ticketId(), state, title, i.reason(), hypothesis, services, i.declaredBy(),
                i.declaredAt(), approvedBy, approvedAt, restoredAt, closedAt, nextUpdateAt, pir, problemId,
                i.version());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
