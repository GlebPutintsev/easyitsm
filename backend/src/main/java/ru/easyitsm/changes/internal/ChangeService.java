package ru.easyitsm.changes.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.changes.api.ChangeCommands;
import ru.easyitsm.changes.api.ChangeOutcome;
import ru.easyitsm.changes.api.ChangeQueries;
import ru.easyitsm.changes.api.ChangeRef;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.ChangeSummary;
import ru.easyitsm.changes.api.ChangeType;
import ru.easyitsm.changes.api.NewChange;
import ru.easyitsm.changes.api.RiskLevel;
import ru.easyitsm.changes.events.ChangeApproved;
import ru.easyitsm.changes.events.ChangeCompleted;
import ru.easyitsm.changes.events.ChangeStateChanged;
import ru.easyitsm.identity.api.AccessDecisions;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTransition;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Жизненный цикл изменения (5.6 ТЗ) и его данные. Статус ведёт этот сервис и переносит в заявку переходом
 * от имени системы; каждое действие проверяет состояние, права на запись и ETag.
 */
@Service
class ChangeService implements ChangeCommands, ChangeQueries {

    /** CHG-11: инциденты за этот срок после работ показываются в разборе. */
    static final Duration RELATED_INCIDENTS_WINDOW = Duration.ofHours(72);

    private final ChangeStore store;
    private final ChangeViews views;
    private final ChangeAccess access;
    private final ChangeSettings settings;
    private final TemplateStore templates;
    private final Conflicts conflicts;
    private final TicketCommands ticketCommands;
    private final TicketQueries tickets;
    private final AccessDecisions decisions;
    private final UserDirectory directory;
    private final Notifier notifier;
    private final ConfigurationItems cmdb;
    private final ApplicationEventPublisher events;

    ChangeService(ChangeStore store, ChangeViews views, ChangeAccess access, ChangeSettings settings,
                  TemplateStore templates, Conflicts conflicts, TicketCommands ticketCommands, TicketQueries tickets,
                  AccessDecisions decisions, UserDirectory directory, Notifier notifier, ConfigurationItems cmdb,
                  ApplicationEventPublisher events) {
        this.store = store;
        this.views = views;
        this.access = access;
        this.settings = settings;
        this.templates = templates;
        this.conflicts = conflicts;
        this.ticketCommands = ticketCommands;
        this.tickets = tickets;
        this.decisions = decisions;
        this.directory = directory;
        this.notifier = notifier;
        this.cmdb = cmdb;
        this.events = events;
    }

    // ================================================================ API для модулей

    @Override
    @Transactional
    public ChangeRef create(NewChange n) {
        ChangeRow c = createInternal(n.type(), n.title(), n.description(), n.authorId(), n.templateId(),
                n.sourceTicketId(), null, n.ciIds(), n.serviceIds(), Channel.API, null);
        return new ChangeRef(c.id, c.number, c.state);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChangeSummary> find(UUID changeId) {
        return store.find(changeId).map(ChangeRow::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChangeSummary> byCi(UUID ciId, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ci", ciId);
        p.put("limit", Math.clamp(limit, 1, 200));
        return store.query("where :ci = any(c.ci_ids) order by c.created_at desc, c.ticket_id desc limit :limit", p)
                .stream().map(ChangeRow::summary).toList();
    }

    // ================================================================ создание

    @Transactional
    ChangeDtos.ChangeView create(ChangeDtos.Create req, Me me) {
        if (req.groupId() != null && !directory.isMember(req.groupId(), me.userId()) && !me.manager()) {
            throw ChangeProblem.invalid("invalid_assignment", "Можно указать только свою группу").with("field", "groupId");
        }
        ChangeType type = req.type() == null ? null : ChangeType.fromCode(req.type());
        ChangeRow c = createInternal(type, req.title(), req.description(), me.userId(), req.templateId(),
                req.sourceTicketId(), req.groupId(), req.ciIds(), req.serviceIds(), Channel.AGENT, me.userId());
        return views.view(c, me);
    }

    /** Создаёт заявку и данные изменения; шаблон подставляет тексты, шаги, КЕ и услуги (CHG-08). */
    ChangeRow createInternal(ChangeType type, String title, String description, UUID authorId, UUID templateId,
                             UUID sourceTicketId, UUID groupId, List<UUID> ciIds, List<UUID> serviceIds,
                             Channel channel, UUID actorId) {
        TemplateStore.Template tpl = null;
        if (templateId != null) {
            tpl = templates.find(templateId).filter(TemplateStore.Template::active)
                    .orElseThrow(() -> ChangeProblem.field("templateId", "Шаблон не найден или выключен"));
        }
        ChangeType effective = type != null ? type : tpl != null ? ChangeType.fromCode(tpl.changeType()) : ChangeType.NORMAL;
        if (effective == ChangeType.STANDARD && (tpl == null || !tpl.preapproved())) {
            throw ChangeProblem.field("templateId",
                    "Стандартное изменение создаётся из шаблона с предварительным утверждением");
        }
        TicketRef ref = ticketCommands.create(new NewTicket(TicketType.CHANGE, title, description, authorId, null, null,
                channel, null, null, null, first(ciIds), null, null, groupId, null, Map.of(), false, actorId));
        ChangeRow c = new ChangeRow();
        c.id = ref.id();
        c.number = ref.number();
        c.title = title;
        c.authorId = authorId;
        c.groupId = groupId;
        c.type = effective;
        c.state = ChangeState.DRAFT;
        c.templateId = templateId;
        c.sourceTicketId = sourceTicketId;
        c.goal = description == null ? "" : description;
        c.timeZone = settings.get().timeZone();
        c.ciIds = distinct(ciIds);
        c.serviceIds = distinct(serviceIds);
        if (tpl != null) {
            applyTemplate(c, tpl);
        }
        store.insert(c);
        if (tpl != null && !tpl.steps().isEmpty()) {
            store.replaceSteps(c.id, stepsFromTemplate(tpl));
        }
        return c;
    }

    private static void applyTemplate(ChangeRow c, TemplateStore.Template tpl) {
        Map<String, Object> t = tpl.content();
        if (c.goal.isBlank() && t.get("goal") instanceof String s) {
            c.goal = s;
        }
        c.justification = t.get("justification") instanceof String s ? s : c.justification;
        c.impact = t.get("impact") instanceof String s ? s : c.impact;
        c.risks = t.get("risks") instanceof String s ? s : c.risks;
        if (t.get("downtimeMinutes") instanceof Number n) {
            c.downtimeMinutes = n.intValue();
        }
        if (t.get("systemCriticality") instanceof String s && !s.isBlank()) {
            c.systemCriticality = s;
        }
        if (c.ciIds.isEmpty()) {
            c.ciIds = tpl.ciIds();
        }
        if (c.serviceIds.isEmpty()) {
            c.serviceIds = tpl.serviceIds();
        }
        if (tpl.riskLevel() != null && tpl.preapproved()) {
            c.riskLevel = RiskLevel.fromCode(tpl.riskLevel());
            c.riskReason = "Шаблон стандартного изменения «" + tpl.name() + "»";
            c.riskManual = true;
        }
    }

    static List<ChangeStore.Step> stepsFromTemplate(TemplateStore.Template tpl) {
        List<ChangeStore.Step> result = new ArrayList<>();
        Map<String, Integer> seq = new HashMap<>();
        for (Map<String, Object> s : tpl.steps()) {
            String phase = String.valueOf(s.getOrDefault("phase", "implement"));
            if (!ChangeStore.PHASES.contains(phase)) {
                continue;
            }
            int n = seq.merge(phase, 1, Integer::sum);
            result.add(new ChangeStore.Step(null, phase, n, str(s.get("title"), "Шаг " + n), str(s.get("description"), ""),
                    null, str(s.get("ownerName"), null), str(s.get("team"), null), str(s.get("contacts"), null), null,
                    s.get("durationMinutes") instanceof Number d ? d.intValue() : 0,
                    Boolean.TRUE.equals(s.get("upto")), str(s.get("interruption"), null),
                    str(s.get("checkCriteria"), null),
                    s.get("rollbackRef") instanceof Number r ? r.intValue() : null, "pending", null, null, null));
        }
        return result;
    }

    /**
     * Заявка типа change создана в обход модуля (REST заявок, проблема, мониторинг): заводит черновик изменения.
     * Если данные уже есть — обновляет копию полей заявки.
     */
    @Transactional
    void onTicket(TicketSnapshot t) {
        if (t.type() != TicketType.CHANGE) {
            return;
        }
        if (store.exists(t.id())) {
            store.syncTicket(t.id(), t.number(), t.title(), t.requesterId(), t.groupId(), t.assigneeId(),
                    t.confidential());
            return;
        }
        ChangeRow c = new ChangeRow();
        c.id = t.id();
        c.number = t.number();
        c.title = t.title();
        c.authorId = t.requesterId();
        c.groupId = t.groupId();
        c.assigneeId = t.assigneeId();
        c.confidential = t.confidential();
        c.type = ChangeType.NORMAL;
        c.state = stateOf(t.statusCode());
        c.timeZone = settings.get().timeZone();
        c.ciIds = t.ciId() == null ? List.of() : List.of(t.ciId());
        c.serviceIds = t.serviceId() == null ? List.of() : List.of(t.serviceId());
        store.insert(c);
    }

    private static ChangeState stateOf(String statusCode) {
        try {
            return ChangeState.fromCode(statusCode);
        } catch (IllegalArgumentException e) {
            return ChangeState.DRAFT;
        }
    }

    // ================================================================ чтение

    @Transactional(readOnly = true)
    ChangeDtos.ChangeView get(UUID id, Me me) {
        ChangeRow c = readable(id, me);
        return views.view(c, me);
    }

    /** Изменение, видимое пользователю, иначе 404 (AT-01). */
    ChangeRow readable(UUID id, Me me) {
        ChangeRow c = store.find(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        if (!access.canRead(c, me, store.approvals(id))) {
            throw ApiException.notFound("Изменение");
        }
        return c;
    }

    record ListFilter(Set<String> states, String type, String risk, UUID ciId, UUID serviceId, UUID groupId,
                      String q, boolean mine, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    CursorPage<ChangeDtos.ChangeItem> list(ListFilter f, PageParams page, Me me) {
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("where ").append(visibility(me, p));
        if (f.states() != null && !f.states().isEmpty()) {
            for (String s : f.states()) {
                ChangeState.fromCode(s);
            }
            where.append(" and c.state = any(cast(:states as text[]))");
            p.put("states", "{" + String.join(",", f.states()) + "}");
        }
        if (f.type() != null) {
            where.append(" and c.change_type = :type");
            p.put("type", ChangeType.fromCode(f.type()).code());
        }
        if (f.risk() != null) {
            where.append(" and c.risk_level = :risk");
            p.put("risk", RiskLevel.fromCode(f.risk()).code());
        }
        if (f.ciId() != null) {
            where.append(" and :ci = any(c.ci_ids)");
            p.put("ci", f.ciId());
        }
        if (f.serviceId() != null) {
            where.append(" and :service = any(c.service_ids)");
            p.put("service", f.serviceId());
        }
        if (f.groupId() != null) {
            where.append(" and c.group_id = :group");
            p.put("group", f.groupId());
        }
        if (f.mine()) {
            where.append(" and (c.author_id = :me or c.assignee_id = :me)");
            p.put("me", me.userId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" and (c.number ilike :q or c.title ilike :q)");
            p.put("q", "%" + f.q().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.from() != null) {
            where.append(" and c.window_end > :from");
            p.put("from", Sql.ts(f.from()));
        }
        if (f.to() != null) {
            where.append(" and c.window_start < :to");
            p.put("to", Sql.ts(f.to()));
        }
        KeysetCursor after = page.keyset();
        if (after != null) {
            where.append(" and (c.created_at, c.ticket_id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid))");
            p.put("afterAt", Sql.ts(Instant.parse(after.sortValue())));
            p.put("afterId", after.id());
        }
        p.put("limit", page.limit() + 1);
        List<ChangeRow> rows = store.query(where + " order by c.created_at desc, c.ticket_id desc limit :limit", p);
        return CursorPage.of(rows, page.limit(), c -> new KeysetCursor(c.createdAt.toString(), c.id))
                .map(ChangeDtos.ChangeItem::of);
    }

    /** Условие списка и календаря: те же правила, что {@link ChangeAccess#canRead}, без проверки заявки. */
    String visibility(Me me, Map<String, Object> p) {
        p.put("viewer", me.userId());
        String own = "(c.author_id = :viewer or c.assignee_id = :viewer)";
        if (me.support() || me.has("AUD")) {
            return "(not c.confidential or " + own + ")";
        }
        return "(" + own + " or exists (select 1 from change_approval a where a.change_id = c.ticket_id"
                + " and a.kind = 'user' and a.approver_id = :viewer))";
    }

    // ================================================================ поля и план

    @Transactional
    ChangeDtos.ChangeView update(UUID id, String ifMatch, ChangeDtos.Update u, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.DRAFT, ChangeState.ASSESSMENT);
        if (u.type() != null) {
            ChangeType type = ChangeType.fromCode(u.type());
            if (type == ChangeType.STANDARD && !preapprovedTemplate(c)) {
                throw ChangeProblem.field("type",
                        "Стандартное изменение создаётся из шаблона с предварительным утверждением");
            }
            if (type != c.type && c.state != ChangeState.DRAFT) {
                throw ChangeProblem.clash("invalid_state", "Тип меняется только в черновике");
            }
            c.type = type;
        }
        c.goal = u.goal() != null ? u.goal() : c.goal;
        c.justification = u.justification() != null ? u.justification() : c.justification;
        c.impact = u.impact() != null ? u.impact() : c.impact;
        c.risks = u.risks() != null ? u.risks() : c.risks;
        if (u.impactRows() != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ChangeDtos.ImpactRow r : u.impactRows()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", r.service());
                row.put("description", r.description() == null ? "" : r.description());
                row.put("duration", r.duration() == null ? "" : r.duration());
                rows.add(row);
            }
            c.impactRows = rows;
        }
        if (u.systemCriticality() != null) {
            c.systemCriticality = u.systemCriticality().isBlank() ? null : u.systemCriticality();
        }
        if (u.timeZone() != null) {
            try {
                ZoneId.of(u.timeZone());
            } catch (RuntimeException e) {
                throw ChangeProblem.field("timeZone", "Неизвестный часовой пояс");
            }
            c.timeZone = u.timeZone();
        }
        if (u.windowStart() != null || u.windowEnd() != null) {
            Instant start = u.windowStart() != null ? u.windowStart() : c.windowStart;
            Instant end = u.windowEnd() != null ? u.windowEnd() : c.windowEnd;
            requireWindow(start, end);
            c.windowStart = start;
            c.windowEnd = end;
        }
        if (Boolean.TRUE.equals(u.clearPrepStart())) {
            c.prepStart = null;
        } else if (u.prepStart() != null) {
            c.prepStart = u.prepStart();
        }
        if (u.downtimeMinutes() != null) {
            c.downtimeMinutes = u.downtimeMinutes();
        }
        if (u.ciIds() != null) {
            c.ciIds = distinct(u.ciIds());
        }
        if (u.serviceIds() != null) {
            c.serviceIds = distinct(u.serviceIds());
        }
        save(c);
        return views.view(c, me);
    }

    @Transactional
    ChangeDtos.ChangeView replacePlan(UUID id, String ifMatch, ChangeDtos.Plan plan, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.DRAFT, ChangeState.ASSESSMENT);
        Map<UUID, ChangeStore.Step> existing = new HashMap<>();
        for (ChangeStore.Step s : store.steps(id)) {
            existing.put(s.id(), s);
        }
        Map<String, Integer> seq = new HashMap<>();
        List<ChangeStore.Step> steps = new ArrayList<>();
        long rollbackCount = plan.steps().stream().filter(s -> "rollback".equals(s.phase())).count();
        for (ChangeDtos.StepInput in : orderedByPhase(plan.steps())) {
            int n = seq.merge(in.phase(), 1, Integer::sum);
            if (in.rollbackRef() != null && in.rollbackRef() > rollbackCount) {
                throw ChangeProblem.field("steps." + in.phase() + "." + n + ".rollbackRef",
                        "В плане возврата нет шага № " + in.rollbackRef());
            }
            ChangeStore.Step old = in.id() == null ? null : existing.get(in.id());
            steps.add(new ChangeStore.Step(old == null ? null : old.id(), in.phase(), n, in.title().strip(),
                    in.description(), in.ownerId(), blankToNull(in.ownerName()), blankToNull(in.team()),
                    blankToNull(in.contacts()), in.plannedStart(),
                    in.durationMinutes() == null ? 0 : in.durationMinutes(), Boolean.TRUE.equals(in.upto()),
                    blankToNull(in.interruption()), blankToNull(in.checkCriteria()), in.rollbackRef(),
                    old == null ? "pending" : old.status(), old == null ? null : old.resultNote(),
                    old == null ? null : old.executedAt(), old == null ? null : old.executedBy()));
        }
        store.replaceSteps(id, steps);
        save(c);
        return views.view(c, me);
    }

    private static List<ChangeDtos.StepInput> orderedByPhase(List<ChangeDtos.StepInput> steps) {
        List<ChangeDtos.StepInput> result = new ArrayList<>(steps);
        result.sort((a, b) -> Integer.compare(ChangeStore.PHASES.indexOf(a.phase()), ChangeStore.PHASES.indexOf(b.phase())));
        return result;
    }

    @Transactional
    ChangeDtos.ChangeView assessRisk(UUID id, String ifMatch, ChangeDtos.Risk risk, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.DRAFT, ChangeState.ASSESSMENT);
        if (c.type == ChangeType.STANDARD) {
            throw ChangeProblem.clash("invalid_state", "Риск стандартного изменения задан шаблоном");
        }
        RiskModel.Result r = RiskModel.evaluate(risk.answers());
        RiskLevel level = risk.level() == null ? r.level() : RiskLevel.fromCode(risk.level());
        boolean manual = level != r.level();
        if (manual && (risk.reason() == null || risk.reason().isBlank())) {
            throw ChangeProblem.field("reason", "Укажите причину ручного изменения уровня риска");
        }
        c.riskAnswers = new LinkedHashMap<>(risk.answers());
        c.riskScore = r.score();
        c.riskLevel = level;
        c.riskManual = manual;
        c.riskReason = manual ? risk.reason().strip() : null;
        c.riskAssessedAt = Sql.now();
        c.riskAssessedBy = me.userId();
        save(c);
        if (manual) {
            ticketCommands.addComment(c.id, me.userId(), "Уровень риска изменён вручную: " + label(r.level()) + " → "
                    + label(level) + ". Причина: " + c.riskReason, ru.easyitsm.tickets.api.Visibility.INTERNAL);
        }
        return views.view(c, me);
    }

    private static String label(RiskLevel l) {
        return switch (l) {
            case LOW -> "низкий";
            case MEDIUM -> "средний";
            case HIGH -> "высокий";
        };
    }

    // ================================================================ цикл до внедрения

    /**
     * «На оценку» (CHG-02): проверяет обязательные поля и планы. Стандартное изменение из шаблона сразу
     * запланировано, экстренное — согласуется сокращённым маршрутом или после работ (CHG-12).
     */
    @Transactional
    ChangeDtos.ChangeView submit(UUID id, String ifMatch, ChangeDtos.SendToApproval req, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.DRAFT);
        List<ChangeStore.Step> steps = store.steps(id);
        requireComplete(c, steps);
        Instant now = Sql.now();
        c.submittedAt = now;
        switch (c.type) {
            case NORMAL -> move(c, ChangeState.ASSESSMENT, null, me);
            case STANDARD -> {
                if (!preapprovedTemplate(c)) {
                    throw ChangeProblem.clash("standard_requires_template",
                            "Стандартное изменение создаётся из шаблона с предварительным утверждением");
                }
                checkConflicts(c, c.windowStart, c.windowEnd, req == null ? null : req.acknowledgeConflicts(), true);
                c.approvedAt = now;
                move(c, ChangeState.SCHEDULED, "Стандартное изменение по утверждённому шаблону", me);
                approved(c, me);
            }
            case EMERGENCY -> {
                checkConflicts(c, c.windowStart, c.windowEnd, req == null ? null : req.acknowledgeConflicts(), false);
                ChangeSettings.Doc s = settings.get();
                RiskLevel level = c.riskLevel != null ? c.riskLevel : RiskLevel.HIGH;
                int round = store.currentRound(c.id) + 1;
                Instant due = ChangeSettings.addBusinessDays(c.windowEnd != null ? c.windowEnd : now,
                        s.emergency().postApprovalBusinessDays(), c.timeZone);
                c.postApprovalDueAt = due;
                buildRoute(c, s.route(level), List.of(), round, true, due);
                if (s.emergency().preApproval()) {
                    store.insertApproval(c.id, "manager", null, null, round, false, null);
                    move(c, ChangeState.APPROVAL, "Экстренное изменение: сокращённый маршрут", me);
                } else {
                    c.approvedAt = now;
                    move(c, ChangeState.SCHEDULED, "Экстренное изменение: согласование после работ", me);
                    approved(c, me);
                }
            }
        }
        return views.view(c, me);
    }

    @Transactional
    ChangeDtos.ChangeView returnToDraft(UUID id, String ifMatch, String reason, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.ASSESSMENT);
        move(c, ChangeState.DRAFT, "Возвращено на доработку: " + reason.strip(), me);
        return views.view(c, me);
    }

    /** «На согласование» (CHG): риск оценён, конфликты показаны, маршрут строится по уровню риска. */
    @Transactional
    ChangeDtos.ChangeView sendToApproval(UUID id, String ifMatch, ChangeDtos.SendToApproval req, Me me) {
        if (!me.manager()) {
            readable(id, me);
            throw ApiException.forbidden();
        }
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.ASSESSMENT);
        requireComplete(c, store.steps(id));
        if (c.riskLevel == null) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "risk_required", "Заполните анкету риска")
                    .with("missing", List.of("risk"));
        }
        checkConflicts(c, c.windowStart, c.windowEnd, req.acknowledgeConflicts(), true);
        List<UUID> extra = req.extraApproverIds() == null ? List.of() : distinct(req.extraApproverIds());
        if (extra.contains(c.authorId)) {
            throw ChangeProblem.field("extraApproverIds", "Автор не может согласовывать своё изменение");
        }
        for (UUID u : extra) {
            if (directory.findUser(u).filter(x -> x.active()).isEmpty()) {
                throw ChangeProblem.field("extraApproverIds", "Согласующий не найден или заблокирован");
            }
        }
        int round = store.currentRound(c.id) + 1;
        int slots = buildRoute(c, settings.get().route(c.riskLevel), extra, round, false, null);
        if (slots == 0) {
            c.approvedAt = Sql.now();
            move(c, ChangeState.SCHEDULED, "Согласование по маршруту не требуется", me);
            approved(c, me);
        } else {
            move(c, ChangeState.APPROVAL, null, me);
            notifyApprovers(c, round);
        }
        return views.view(c, me);
    }

    /** @return сколько слотов создано */
    private int buildRoute(ChangeRow c, ChangeSettings.Route route, List<UUID> extra, int round, boolean postFactum,
                           Instant due) {
        int n = 0;
        if (route.manager()) {
            store.insertApproval(c.id, "manager", null, null, round, postFactum, due);
            n++;
        }
        if (route.groupId() != null) {
            store.insertApproval(c.id, "group", route.groupId(), null, round, postFactum, due);
            n++;
        }
        for (UUID u : extra) {
            store.insertApproval(c.id, "user", null, u, round, postFactum, due);
            n++;
        }
        if (route.cab()) {
            store.insertApproval(c.id, "cab", null, null, round, postFactum, due);
            n++;
        }
        return n;
    }

    /** Решение согласующего по слоту (10.8). Автор не утверждает своё изменение (ACC-05, AT-05). */
    @Transactional
    ChangeDtos.ChangeView decide(UUID id, UUID approvalId, ChangeDtos.Decision d, Me me) {
        ChangeRow c = store.lock(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        List<ChangeStore.Approval> approvals = store.approvals(id);
        if (!access.canRead(c, me, approvals)) {
            throw ApiException.notFound("Изменение");
        }
        ChangeStore.Approval a = approvals.stream().filter(x -> x.id().equals(approvalId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Согласование"));
        if ("cab".equals(a.kind())) {
            throw ChangeProblem.clash("cab_decision_required", "Решение по этому согласованию принимает CAB на заседании");
        }
        if (!access.isApprover(a, me)) {
            throw ApiException.forbidden();
        }
        decisions.requireNotAuthor(c.authorId);
        if (!a.pending()) {
            throw ChangeProblem.clash("already_decided", "Согласование уже решено");
        }
        if (!a.postFactum()) {
            requireState(c, ChangeState.APPROVAL);
        }
        boolean approve = "approve".equals(d.decision());
        if (!approve && (d.comment() == null || d.comment().isBlank())) {
            throw ChangeProblem.field("comment", "Укажите причину отклонения");
        }
        Instant now = Sql.now();
        store.decide(a.id(), approve ? "approved" : "rejected", blankToNull(d.comment()), me.userId(), now);
        afterDecision(c, a, approve, d.comment(), me);
        return views.view(store.find(id).orElseThrow(), me);
    }

    /**
     * Итог решения слота: при отказе до работ изменение отклонено, при всех утверждённых — запланировано.
     * Последующее согласование экстренного изменения только фиксируется.
     */
    void afterDecision(ChangeRow c, ChangeStore.Approval a, boolean approve, String comment, Me me) {
        if (a.postFactum()) {
            if (!approve) {
                ticketCommands.addComment(c.id, me == null ? null : me.userId(),
                        "Последующее согласование экстренного изменения отклонено: " + comment,
                        ru.easyitsm.tickets.api.Visibility.INTERNAL);
                notify(c.authorId, c, "Экстренное изменение не согласовано после работ", comment);
            }
            save(c);
            return;
        }
        if (!approve) {
            store.supersedePending(c.id, false);
            move(c, ChangeState.REJECTED, "Отклонено: " + comment, me);
            notify(c.authorId, c, "Изменение отклонено", comment);
            return;
        }
        boolean all = store.approvals(c.id).stream()
                .filter(x -> !x.postFactum() && x.round() == a.round() && x.active())
                .allMatch(x -> "approved".equals(x.decision()));
        if (all) {
            c.approvedAt = Sql.now();
            move(c, ChangeState.SCHEDULED, "Все согласования получены", me);
            approved(c, me);
            notify(c.authorId, c, "Изменение согласовано", null);
        } else {
            save(c);
        }
    }

    /** Перенос окна (календарь, 10.10): подтверждение и пересогласование. */
    @Transactional
    ChangeDtos.ChangeView reschedule(UUID id, String ifMatch, ChangeDtos.Reschedule req, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.APPROVAL, ChangeState.SCHEDULED);
        requireWindow(req.windowStart(), req.windowEnd());
        checkConflicts(c, req.windowStart(), req.windowEnd(), req.acknowledgeConflicts(), c.type != ChangeType.EMERGENCY);
        Instant oldStart = c.windowStart;
        c.windowStart = req.windowStart();
        c.windowEnd = req.windowEnd();
        String note = "Окно работ перенесено с " + oldStart + " на " + c.windowStart + ". Причина: " + req.reason().strip();
        boolean preApprovals = store.approvals(c.id).stream().anyMatch(a -> !a.postFactum() && a.active());
        if (c.type == ChangeType.NORMAL || (c.type == ChangeType.EMERGENCY && preApprovals)) {
            store.supersedePending(c.id, false);
            int round = store.currentRound(c.id) + 1;
            ChangeSettings.Doc s = settings.get();
            List<UUID> extra = store.approvals(c.id).stream()
                    .filter(a -> "user".equals(a.kind()) && !a.postFactum()).map(ChangeStore.Approval::approverId)
                    .distinct().toList();
            int slots = c.type == ChangeType.EMERGENCY
                    ? insertOne(c.id, round)
                    : buildRoute(c, s.route(c.riskLevel == null ? RiskLevel.HIGH : c.riskLevel), extra, round, false, null);
            c.approvedAt = null;
            if (slots == 0) {
                c.approvedAt = Sql.now();
                if (c.state == ChangeState.APPROVAL) {
                    move(c, ChangeState.SCHEDULED, note, me);
                } else {
                    save(c);
                    ticketCommands.addComment(c.id, me.userId(), note, ru.easyitsm.tickets.api.Visibility.INTERNAL);
                }
                approved(c, me);
            } else if (c.state == ChangeState.SCHEDULED) {
                move(c, ChangeState.APPROVAL, note + ". Нужно повторное согласование", me);
                notifyApprovers(c, round);
            } else {
                save(c);
                ticketCommands.addComment(c.id, me.userId(), note, ru.easyitsm.tickets.api.Visibility.INTERNAL);
                notifyApprovers(c, round);
            }
        } else {
            if (c.type == ChangeType.EMERGENCY && c.postApprovalDueAt != null) {
                c.postApprovalDueAt = ChangeSettings.addBusinessDays(c.windowEnd,
                        settings.get().emergency().postApprovalBusinessDays(), c.timeZone);
                store.setPostFactumDue(c.id, c.postApprovalDueAt);
            }
            save(c);
            ticketCommands.addComment(c.id, me.userId(), note, ru.easyitsm.tickets.api.Visibility.INTERNAL);
        }
        return views.view(c, me);
    }

    private int insertOne(UUID changeId, int round) {
        store.insertApproval(changeId, "manager", null, null, round, false, null);
        return 1;
    }

    @Transactional
    ChangeDtos.ChangeView cancel(UUID id, String ifMatch, String reason, Me me) {
        ChangeRow c = store.lock(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        if (!access.canRead(c, me, store.approvals(id))) {
            throw ApiException.notFound("Изменение");
        }
        if (!me.manager() && !me.is(c.authorId)) {
            throw ApiException.forbidden();
        }
        ETags.requireMatch(ifMatch, c.version);
        if (!c.state.beforeImplementation()) {
            throw ChangeProblem.clash("invalid_state", "Отменить можно только до начала внедрения");
        }
        store.supersedePending(c.id, false);
        store.supersedePending(c.id, true);
        c.cancelReason = reason.strip();
        move(c, ChangeState.CANCELLED, "Отменено: " + c.cancelReason, me);
        return views.view(c, me);
    }

    // ================================================================ внедрение и разбор

    /** «Начать внедрение»: в окне или с подтверждением выхода за окно. */
    @Transactional
    ChangeDtos.ChangeView start(UUID id, String ifMatch, ChangeDtos.Start req, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.SCHEDULED);
        Instant now = Sql.now();
        boolean inWindow = c.windowStart != null && c.windowEnd != null && !now.isBefore(c.windowStart)
                && now.isBefore(c.windowEnd);
        String reason = req == null ? null : blankToNull(req.outsideWindowReason());
        if (!inWindow && c.type != ChangeType.EMERGENCY && reason == null) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "outside_window",
                    "Сейчас вне окна работ. Подтвердите начало и укажите причину")
                    .with("windowStart", c.windowStart).with("windowEnd", c.windowEnd);
        }
        c.actualStart = now;
        if (reason != null) {
            c.review.put("outsideWindowReason", reason);
        }
        move(c, ChangeState.IMPLEMENTATION, reason == null ? null : "Начато вне окна работ: " + reason, me);
        return views.view(c, me);
    }

    @Transactional
    ChangeDtos.ChangeView stepResult(UUID id, UUID stepId, ChangeDtos.StepResult req, Me me) {
        ChangeRow c = store.lock(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        if (!access.canRead(c, me, store.approvals(id))) {
            throw ApiException.notFound("Изменение");
        }
        if (!access.canWork(c, me)) {
            throw ApiException.forbidden();
        }
        requireState(c, ChangeState.IMPLEMENTATION, ChangeState.VERIFICATION);
        ChangeStore.Step step = store.steps(id).stream().filter(s -> s.id().equals(stepId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Шаг"));
        if ("rollback".equals(step.phase()) && c.rollbackStartedAt == null) {
            throw ChangeProblem.clash("rollback_not_started", "Сначала запустите возврат");
        }
        if ("failed".equals(req.status()) && blankToNull(req.note()) == null) {
            throw ChangeProblem.field("note", "Опишите, что пошло не так");
        }
        boolean reset = "pending".equals(req.status());
        store.updateStepResult(stepId, req.status(), blankToNull(req.note()), reset ? null : Sql.now(),
                reset ? null : me.userId());
        if ("failed".equals(req.status())) {
            ticketCommands.addComment(c.id, me.userId(), "Шаг «" + step.title() + "» не удался: " + req.note().strip(),
                    ru.easyitsm.tickets.api.Visibility.INTERNAL);
        }
        save(c);
        return views.view(c, me);
    }

    /** «Запустить возврат»: открывает шаги возврата и оповещает участников. */
    @Transactional
    ChangeDtos.ChangeView startRollback(UUID id, String ifMatch, String reason, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.IMPLEMENTATION, ChangeState.VERIFICATION);
        if (c.rollbackStartedAt != null) {
            throw ChangeProblem.clash("rollback_started", "Возврат уже запущен");
        }
        c.rollbackStartedAt = Sql.now();
        c.review.put("rollbackReason", reason.strip());
        save(c);
        ticketCommands.addComment(c.id, me.userId(), "Запущен план возврата. Причина: " + reason.strip(),
                ru.easyitsm.tickets.api.Visibility.INTERNAL);
        Set<UUID> people = new LinkedHashSet<>();
        people.add(c.authorId);
        if (c.assigneeId != null) {
            people.add(c.assigneeId);
        }
        people.addAll(tickets.watchers(c.id));
        people.remove(me.userId());
        for (UUID u : people) {
            notify(u, c, "Запущен возврат изменения", reason.strip());
        }
        return views.view(c, me);
    }

    /** «Шаги выполнены»: внедрение → проверка. Все шаги внедрения должны иметь результат. */
    @Transactional
    ChangeDtos.ChangeView toVerification(UUID id, String ifMatch, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.IMPLEMENTATION);
        List<String> pending = store.steps(id).stream()
                .filter(s -> "implement".equals(s.phase()) && "pending".equals(s.status()))
                .map(s -> s.seq() + ". " + s.title()).toList();
        if (!pending.isEmpty()) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "steps_pending", "Отметьте результат каждого шага внедрения")
                    .with("steps", pending);
        }
        move(c, ChangeState.VERIFICATION, null, me);
        return views.view(c, me);
    }

    @Transactional
    ChangeDtos.ChangeView backToImplementation(UUID id, String ifMatch, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.VERIFICATION);
        move(c, ChangeState.IMPLEMENTATION, "Возвращено во внедрение", me);
        return views.view(c, me);
    }

    /** «Завершить»: результат, фактическое окно, заключение (CHG-11). */
    @Transactional
    ChangeDtos.ChangeView complete(UUID id, String ifMatch, ChangeDtos.Complete req, Me me) {
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.IMPLEMENTATION, ChangeState.VERIFICATION);
        ChangeOutcome outcome = ChangeOutcome.fromCode(req.outcome());
        if (outcome != ChangeOutcome.SUCCESSFUL && blankToNull(req.conclusion()) == null) {
            throw ChangeProblem.field("conclusion", "Опишите, что не удалось и почему");
        }
        Instant start = req.actualStart() != null ? req.actualStart() : c.actualStart;
        Instant end = req.actualEnd() != null ? req.actualEnd() : Sql.now();
        if (start != null && !end.isAfter(start)) {
            throw ChangeProblem.field("actualEnd", "Окончание должно быть позже начала");
        }
        c.outcome = outcome;
        c.actualStart = start;
        c.actualEnd = end;
        c.conclusion = blankToNull(req.conclusion());
        if (c.type == ChangeType.EMERGENCY && c.postApprovalDueAt != null) {
            c.postApprovalDueAt = ChangeSettings.addBusinessDays(end, settings.get().emergency().postApprovalBusinessDays(),
                    c.timeZone);
            store.setPostFactumDue(c.id, c.postApprovalDueAt);
        }
        move(c, ChangeState.REVIEW, "Работы завершены: " + outcomeLabel(outcome), me);
        events.publishEvent(new ChangeCompleted(c.tenantId, c.updatedAt, me.userId(), c.id, c.number, outcome.code(),
                c.actualStart, c.actualEnd, c.ciIds, c.serviceIds));
        return views.view(c, me);
    }

    static String outcomeLabel(ChangeOutcome o) {
        return switch (o) {
            case SUCCESSFUL -> "успешно";
            case PARTIAL -> "частично";
            case ROLLED_BACK -> "откат";
        };
    }

    /** «Закрыть» после разбора (CHG): заключение, связанные инциденты, последующее согласование получено. */
    @Transactional
    ChangeDtos.ChangeView close(UUID id, String ifMatch, ChangeDtos.Close req, Me me) {
        if (!me.manager()) {
            readable(id, me);
            throw ApiException.forbidden();
        }
        ChangeRow c = forWork(id, ifMatch, me);
        requireState(c, ChangeState.REVIEW);
        if (store.approvals(id).stream().anyMatch(a -> a.postFactum() && a.pending())) {
            throw ChangeProblem.clash("post_approval_pending",
                    "Экстренное изменение ещё не согласовано после работ");
        }
        Map<String, Object> review = new LinkedHashMap<>(c.review);
        for (Map.Entry<String, Object> e : req.answers().entrySet()) {
            if (REVIEW_KEYS.contains(e.getKey())) {
                Object v = e.getValue();
                if (v instanceof String s && s.length() > 4000) {
                    throw ChangeProblem.field("answers." + e.getKey(), "Слишком длинный ответ");
                }
                review.put(e.getKey(), v);
            }
        }
        review.put("relatedIncidents", relatedIncidents(c).stream().map(ChangeDtos.RelatedIncident::number).toList());
        c.review = review;
        c.conclusion = req.conclusion().strip();
        c.closedAt = Sql.now();
        move(c, ChangeState.CLOSED, null, me);
        return views.view(c, me);
    }

    /** Вопросы отчёта о выполнении работ, которые сохраняются в разборе. */
    static final Set<String> REVIEW_KEYS = Set.of("goalAchieved", "enoughInfo", "causedIncidents", "incidentNumbers",
            "incidentCauses", "consequences", "correctiveActions", "onTime", "impactAsPlanned", "docsUpdated",
            "rollbackWorked", "comment");

    // ================================================================ анализ

    @Transactional(readOnly = true)
    List<ChangeDtos.Conflict> conflicts(UUID id, Me me) {
        ChangeRow c = readable(id, me);
        return conflicts.find(c.windowStart, c.windowEnd, c.ciIds, c.serviceIds, c.id);
    }

    @Transactional(readOnly = true)
    List<ChangeDtos.Conflict> checkConflicts(ChangeDtos.ConflictQuery q, Me me) {
        if (q.excludeId() != null) {
            readable(q.excludeId(), me);
        }
        requireWindow(q.windowStart(), q.windowEnd());
        Map<String, Object> p = new LinkedHashMap<>();
        String visible = visibility(me, p);
        List<ChangeDtos.Conflict> found = conflicts.find(q.windowStart(), q.windowEnd(), distinct(q.ciIds()),
                distinct(q.serviceIds()), q.excludeId());
        // Конфиденциальные изменения других групп не раскрываются даже номером.
        Set<UUID> hidden = new java.util.HashSet<>();
        List<UUID> ids = found.stream().map(ChangeDtos.Conflict::changeId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            p.put("ids", Sql.array(ids));
            Set<UUID> seen = new java.util.HashSet<>(store.query("where c.ticket_id = any(cast(:ids as uuid[])) and "
                    + visible, p).stream().map(r -> r.id).toList());
            ids.stream().filter(i -> !seen.contains(i)).forEach(hidden::add);
        }
        return found.stream().map(c -> c.changeId() != null && hidden.contains(c.changeId())
                ? new ChangeDtos.Conflict(c.kind(), null, null, "Изменение без доступа", c.state(), null,
                c.sharedCiIds(), c.sharedServiceIds(), c.from(), c.to()) : c).toList();
    }

    @Transactional(readOnly = true)
    List<ChangeDtos.RelatedIncident> relatedIncidents(UUID id, Me me) {
        return relatedIncidents(readable(id, me));
    }

    /** Инциденты по КЕ и услугам изменения от начала работ до 72 часов после (CHG-11). */
    List<ChangeDtos.RelatedIncident> relatedIncidents(ChangeRow c) {
        Instant from = c.actualStart != null ? c.actualStart : c.windowStart;
        Instant end = c.actualEnd != null ? c.actualEnd : c.windowEnd;
        if (from == null || end == null) {
            return List.of();
        }
        return tickets.createdBetween(TicketType.INCIDENT, from, end.plus(RELATED_INCIDENTS_WINDOW), c.ciIds,
                        c.serviceIds, 100).stream()
                .map(t -> new ChangeDtos.RelatedIncident(t.id(), t.number(), t.title(), t.statusCode(),
                        t.priority() == null ? null : t.priority().name(), t.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    ChangeDtos.Impact impact(UUID id, int depth, Me me) {
        ChangeRow c = readable(id, me);
        List<ChangeDtos.CiView> cis = cmdb.find(c.ciIds).stream()
                .map(ci -> new ChangeDtos.CiView(ci.id(), ci.name(), ci.className(), ci.service())).toList();
        return new ChangeDtos.Impact(cmdb.available(), cis,
                c.ciIds.isEmpty() ? List.of() : cmdb.impactedServices(c.ciIds, Math.clamp(depth, 1, 5)), c.serviceIds);
    }

    @Transactional(readOnly = true)
    List<ChangeDtos.PendingApproval> pendingApprovals(Me me) {
        List<UUID> groups = List.copyOf(directory.groupsOf(me.userId()));
        List<ChangeDtos.PendingApproval> result = new ArrayList<>();
        Map<UUID, ChangeRow> rows = new HashMap<>();
        for (ChangeStore.Approval a : store.pendingFor(me.userId(), me.manager(), groups, 200)) {
            ChangeRow c = rows.computeIfAbsent(a.changeId(), cid -> store.find(cid).orElse(null));
            if (c == null || (!a.postFactum() && c.state != ChangeState.APPROVAL)) {
                continue;
            }
            result.add(new ChangeDtos.PendingApproval(new ChangeDtos.ApprovalView(a.id(), a.kind(), a.groupId(),
                    a.approverId(), a.round(), a.postFactum(), a.decision(), a.comment(), a.decidedBy(), a.decidedAt(),
                    a.dueAt(), true), ChangeDtos.ChangeItem.of(c)));
        }
        return result;
    }

    // ================================================================ общее

    /** Изменение для записи: видимое (иначе 404), пользователь ведёт его (иначе 403), ETag совпадает. */
    ChangeRow forWork(UUID id, String ifMatch, Me me) {
        ChangeRow c = store.lock(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        if (!access.canRead(c, me, store.approvals(id))) {
            throw ApiException.notFound("Изменение");
        }
        if (!access.canWork(c, me)) {
            throw ApiException.forbidden();
        }
        ETags.requireMatch(ifMatch, c.version);
        return c;
    }

    static void requireState(ChangeRow c, ChangeState... allowed) {
        for (ChangeState s : allowed) {
            if (c.state == s) {
                return;
            }
        }
        throw ChangeProblem.clash("invalid_state", "Действие недоступно в статусе «" + stateLabel(c.state) + "»")
                .with("state", c.state.code());
    }

    static String stateLabel(ChangeState s) {
        return switch (s) {
            case DRAFT -> "Черновик";
            case ASSESSMENT -> "Оценка";
            case APPROVAL -> "Согласование";
            case SCHEDULED -> "Запланировано";
            case IMPLEMENTATION -> "Внедрение";
            case VERIFICATION -> "Проверка";
            case REVIEW -> "Разбор";
            case CLOSED -> "Закрыто";
            case REJECTED -> "Отклонено";
            case CANCELLED -> "Отменено";
        };
    }

    private void requireComplete(ChangeRow c, List<ChangeStore.Step> steps) {
        List<String> missing = views.missing(c, steps).stream().filter(m -> !"risk".equals(m)).toList();
        if (!missing.isEmpty()) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "fields_required",
                    "Заполните обязательные поля и планы изменения").with("missing", missing);
        }
    }

    private static void requireWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            throw ChangeProblem.field("windowEnd", "Укажите начало и конец окна работ");
        }
        if (!end.isAfter(start)) {
            throw ChangeProblem.field("windowEnd", "Конец окна должен быть позже начала");
        }
    }

    /**
     * Заморозка запрещает работы, кроме экстренных; пересечение с другими изменениями на тех же КЕ и услугах
     * требует подтверждения с причиной (AT-06).
     */
    private void checkConflicts(ChangeRow c, Instant start, Instant end, String ack, boolean freezeBlocks) {
        List<ChangeDtos.Conflict> found = conflicts.find(start, end, c.ciIds, c.serviceIds, c.id);
        if (freezeBlocks && Conflicts.hasFreeze(found)) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "change_freeze",
                    "Окно работ попадает в период заморозки изменений").with("conflicts", found);
        }
        if (!found.isEmpty() && blankToNull(ack) == null) {
            throw new ChangeProblem(HttpStatus.CONFLICT, "change_conflicts",
                    "Есть пересечения с другими изменениями или заморозками. Подтвердите с причиной")
                    .with("conflicts", found);
        }
        c.conflictsAck = found.isEmpty() ? null : ack.strip();
    }

    private boolean preapprovedTemplate(ChangeRow c) {
        return c.templateId != null && templates.find(c.templateId).map(TemplateStore.Template::preapproved).orElse(false);
    }

    /** Новый статус в изменении и в заявке (переход от имени системы) и событие. */
    void move(ChangeRow c, ChangeState to, String comment, Me me) {
        ChangeState from = c.state;
        ticketCommands.transition(c.id, new TicketTransition(to.code(), comment, null, null, null), "changes");
        c.state = to;
        save(c);
        events.publishEvent(new ChangeStateChanged(c.tenantId, c.updatedAt, me == null ? null : me.userId(), c.id,
                c.number, from.code(), to.code()));
    }

    private void approved(ChangeRow c, Me me) {
        events.publishEvent(new ChangeApproved(c.tenantId, c.updatedAt, me == null ? null : me.userId(), c.id,
                c.number, c.type.code(), c.riskLevel == null ? null : c.riskLevel.code(), c.windowStart, c.windowEnd,
                c.ciIds, c.serviceIds));
    }

    void save(ChangeRow c) {
        if (!store.update(c)) {
            throw ApiException.conflict("version_conflict", "Изменение обновлено другим пользователем. Обновите и повторите");
        }
    }

    private void notifyApprovers(ChangeRow c, int round) {
        Set<UUID> people = new LinkedHashSet<>();
        for (ChangeStore.Approval a : store.approvals(c.id)) {
            if (a.round() != round || !a.pending() || a.postFactum()) {
                continue;
            }
            if ("user".equals(a.kind())) {
                people.add(a.approverId());
            } else if ("group".equals(a.kind())) {
                people.addAll(directory.activeMembersOf(a.groupId()));
            }
        }
        people.remove(c.authorId);
        for (UUID u : people) {
            notify(u, c, "Изменение ждёт согласования", null);
        }
    }

    private void notify(UUID userId, ChangeRow c, String title, String body) {
        if (userId == null || CurrentActor.userId().map(userId::equals).orElse(false)) {
            return;
        }
        notifier.notify(userId, "change", title + ": " + c.number, (c.title + (body == null ? "" : "\n" + body)),
                "/agent/changes/" + c.id, false);
    }

    private static <T> T first(List<T> list) {
        return list == null || list.isEmpty() ? null : list.getFirst();
    }

    static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String str(Object v, String fallback) {
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
