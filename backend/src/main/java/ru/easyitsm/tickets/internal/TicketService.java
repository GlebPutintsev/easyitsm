package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.SecretFinding;
import ru.easyitsm.tickets.api.SecretScanner;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTransition;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;
import ru.easyitsm.tickets.events.TicketCommentAdded;
import ru.easyitsm.tickets.events.TicketCreated;
import ru.easyitsm.tickets.events.TicketEscalated;

/**
 * Заявка: создание, изменение, переходы, лента, эскалация. Реализует контракт для других модулей
 * ({@link TicketCommands}, {@link TicketQueries}) и обслуживает REST.
 */
@Service
class TicketService implements TicketCommands, TicketQueries {

    private final TicketStore store;
    private final TicketEngine engine;
    private final TicketAccess access;
    private final Feed feed;
    private final Watchers watchers;
    private final CustomFields customFields;
    private final Routing routing;
    private final SettingsStore settings;
    private final SecretScanner secrets;
    private final ApplicationEventPublisher events;

    TicketService(TicketStore store, TicketEngine engine, TicketAccess access, Feed feed, Watchers watchers,
                  CustomFields customFields, Routing routing, SettingsStore settings, SecretScanner secrets,
                  ApplicationEventPublisher events) {
        this.store = store;
        this.engine = engine;
        this.access = access;
        this.feed = feed;
        this.watchers = watchers;
        this.customFields = customFields;
        this.routing = routing;
        this.settings = settings;
        this.secrets = secrets;
        this.events = events;
    }

    // ================================================================ чтение

    @Override
    @Transactional(readOnly = true)
    public Optional<TicketSnapshot> find(UUID ticketId) {
        return store.find(ticketId).map(TicketRow::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TicketSnapshot> findByNumber(String number) {
        return store.findByNumber(number).map(TicketRow::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TicketSnapshot> findVisible(UUID ticketId) {
        Actor actor = Actor.current();
        return store.find(ticketId).filter(t -> access.canRead(t, actor)).map(TicketRow::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ru.easyitsm.tickets.api.TicketPermissions> permissions(UUID ticketId) {
        Actor actor = Actor.current();
        return store.find(ticketId).filter(t -> access.canRead(t, actor))
                .map(t -> new ru.easyitsm.tickets.api.TicketPermissions(t.snapshot(),
                        !actor.roles().equals(Set.of("AUD")), actor.canSeeInternal(), actor.isSupport(),
                        t.statusCategory.isFinal(), access.canWork(t, actor)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ru.easyitsm.tickets.api.TicketComment> findComment(UUID ticketId, UUID activityId) {
        return feed.find(ticketId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> watchers(UUID ticketId) {
        return watchers.userIds(ticketId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketSnapshot> openForObjects(java.util.Collection<UUID> objectIds, int limit) {
        if (objectIds.isEmpty()) {
            return List.of();
        }
        UUID[] ids = objectIds.stream().distinct().toArray(UUID[]::new);
        return store.query("""
                        where (t.ci_id = any(:ids) or t.service_id = any(:ids))
                          and t.status_category not in ('draft', 'resolved', 'closed', 'cancelled')
                        order by t.created_at desc, t.id desc
                        limit :limit""",
                        java.util.Map.of("ids", ids, "limit", Math.clamp(limit, 1, 500)))
                .stream().map(TicketRow::snapshot).toList();
    }

    @Transactional(readOnly = true)
    TicketRow readTx(String ref, Actor actor) {
        return read(ref, actor);
    }

    /** Заявка по UUID или номеру, видимая пользователю, иначе 404. */
    TicketRow read(String ref, Actor actor) {
        return access.requireRead(resolve(ref, false), actor);
    }

    TicketRow resolve(String ref, boolean lock) {
        Optional<TicketRow> row;
        UUID id = parseUuid(ref);
        if (id != null) {
            row = lock ? store.lock(id) : store.find(id);
        } else {
            row = store.findByNumber(ref);
            if (lock && row.isPresent()) {
                row = store.lock(row.get().id);
            }
        }
        return row.orElseThrow(() -> ApiException.notFound("Заявка"));
    }

    /** Заявка под блокировкой для изменения: видна пользователю, версия совпадает с If-Match. */
    TicketRow forUpdate(String ref, Actor actor, String ifMatch) {
        TicketRow t = access.requireRead(resolve(ref, true), actor);
        if (ifMatch != null || !actor.system()) {
            ETags.requireMatch(ifMatch, t.version);
        }
        return t;
    }

    static UUID parseUuid(String ref) {
        try {
            return ref != null && ref.length() == 36 ? UUID.fromString(ref) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ================================================================ создание

    @Override
    @Transactional
    public TicketRef create(NewTicket n) {
        Actor actor = Actor.system(n.actorId());
        Draft d = new Draft(n.type(), n.title(), n.description(), n.requesterId(), n.createdForId(), n.organizationId(),
                n.channel(), n.impact(), n.urgency(), null, null, n.groupId(), null, n.serviceId(), n.ciId(),
                n.categoryId(), n.catalogItemId(), n.parentId(), false, null, null, n.custom(), List.of(),
                n.awaitingApproval());
        TicketRow t = create(d, actor);
        return new TicketRef(t.id, t.number, t.status, t.statusCategory);
    }

    /** Данные новой заявки, общие для REST, других модулей и подзадач. */
    record Draft(TicketType type, String title, String description, UUID requesterId, UUID createdForId,
                 UUID organizationId, Channel channel, Level impact, Level urgency, Priority priority,
                 String priorityReason, UUID groupId, UUID assigneeId, UUID serviceId, UUID ciId, UUID categoryId,
                 UUID catalogItemId, UUID parentId, boolean confidential, UUID securityGroupId, Instant dueAt,
                 Map<String, Object> custom, List<UUID> watcherIds, boolean awaitingApproval) {
    }

    @Transactional
    TicketRow createFromRest(TicketRequests.Create req, Actor actor) {
        if (!actor.canCreate()) {
            throw ApiException.forbidden();
        }
        boolean support = actor.isSupport();
        UUID requester = req.requesterId() != null ? req.requesterId() : actor.userId();
        if (!support && (!requester.equals(actor.userId()) || req.groupId() != null || req.assigneeId() != null
                || req.priority() != null || Boolean.TRUE.equals(req.confidential()) || req.organizationId() != null)) {
            throw ApiException.forbidden();
        }
        access.requireValidAssignment(req.groupId(), req.assigneeId(), actor);
        String text = req.title() + "\n" + (req.description() == null ? "" : req.description());
        String policy = secretsPolicy(req.secrets(), actor);
        String title = req.title();
        String description = req.description();
        if (!"allow".equals(policy)) {
            List<SecretFinding> found = secrets.scan(text);
            if (!found.isEmpty()) {
                if ("warn".equals(policy)) {
                    throw TicketProblem.secrets(found);
                }
                title = secrets.mask(title);
                description = description == null ? null : secrets.mask(description);
            }
        }
        Channel channel = req.channel() != null ? Channel.fromCode(req.channel())
                : support ? Channel.AGENT : Channel.PORTAL;
        Draft d = new Draft(TicketType.fromCode(req.type()), title, description, requester, req.createdForId(),
                req.organizationId(), channel, Level.fromCode(req.impact()), Level.fromCode(req.urgency()),
                Priority.fromCode(req.priority()), req.priorityReason(), req.groupId(), req.assigneeId(),
                req.serviceId(), req.ciId(), req.categoryId(), null, null, Boolean.TRUE.equals(req.confidential()),
                req.securityGroupId(), req.dueAt(), req.custom(), req.watcherIds() == null ? List.of() : req.watcherIds(),
                false);
        return create(d, actor);
    }

    TicketRow create(Draft d, Actor actor) {
        ChangeContext ctx = new ChangeContext(actor);
        TicketRow t = new TicketRow();
        t.id = ru.easyitsm.platform.id.Ids.newId();
        t.tenantId = TenantContext.require();
        t.type = d.type();
        t.number = store.nextNumber(d.type());
        t.title = d.title().strip();
        if (t.title.isEmpty() || t.title.length() > 255) {
            throw TicketProblem.fieldRequired("title", "Тема — от 1 до 255 символов");
        }
        t.description = d.description() == null ? "" : d.description();
        t.requesterId = d.requesterId();
        t.createdForId = d.createdForId();
        t.organizationId = d.organizationId();
        t.channel = d.channel();
        t.impact = d.impact() != null ? d.impact() : Level.LOW;
        t.urgency = d.urgency() != null ? d.urgency() : Level.MEDIUM;
        if (d.priority() != null) {
            requireReason(d.priorityReason(), "priorityReason", "Укажите причину ручного приоритета");
            t.priority = d.priority();
            t.priorityManual = true;
        } else {
            t.priority = settings.priorityMatrix().priority(t.impact, t.urgency);
        }
        t.groupId = d.groupId();
        t.assigneeId = d.assigneeId();
        t.serviceId = d.serviceId();
        t.ciId = d.ciId();
        t.categoryId = d.categoryId();
        t.catalogItemId = d.catalogItemId();
        t.parentId = d.parentId();
        t.confidential = d.confidential();
        t.securityGroupId = d.securityGroupId();
        t.dueAt = d.dueAt();
        t.identityCheckRequired = t.categoryId != null
                && settings.general().identityCheckCategoryIds().contains(t.categoryId);
        t.custom = customFields.apply(t.type, Map.of(), d.custom(), true);

        Workflow wf = engine.workflow(t.type);
        Workflow.Status initial = d.awaitingApproval()
                ? wf.firstOf(StatusCategory.APPROVAL).orElseThrow(() -> TicketProblem.clash("transition_not_allowed",
                        "В цикле типа нет статуса согласования"))
                : wf.initial();
        t.status = initial.code();
        t.statusCategory = initial.categoryEnum();

        Routing.Result routed = null;
        if (t.groupId == null && t.statusCategory != StatusCategory.APPROVAL && t.statusCategory != StatusCategory.DRAFT) {
            routed = routing.route(t);
            t.groupId = routed.groupId();
            t.assigneeId = routed.assigneeId();
        } else if (t.groupId != null && t.assigneeId == null && t.statusCategory == StatusCategory.NEW) {
            t.assigneeId = routing.dispatch(t.groupId).orElse(null);
        }
        if (t.groupId != null) {
            ChangeContext auto = new ChangeContext(Actor.system(actor.userId()));
            engine.onGroupAssigned(t, auto);
        }
        t.createdAt = ctx.now;
        t.createdBy = actor.userId();
        t.updatedAt = ctx.now;
        t.updatedBy = actor.userId();
        t.version = 0;
        store.insert(t);

        if (routed != null && routed.groupId() != null) {
            feed.add(t.id, "system", "internal", null, false, "",
                    Map.of("event", "routed", "source", routed.source(), "groupId", routed.groupId().toString()),
                    List.of(), ctx.now);
        }
        events.publishEvent(new TicketCreated(t.tenantId, ctx.now, actor.userId(), t.snapshot()));
        for (UUID w : d.watcherIds()) {
            watchers.add(t, w, "added", actor);
        }
        return t;
    }

    // ================================================================ изменение полей

    @Transactional
    TicketRow update(String ref, String ifMatch, TicketRequests.Update req, Actor actor) {
        access.requireSupport(actor);
        TicketRow before = access.requireWork(forUpdate(ref, actor, ifMatch), actor);
        requireActive(before);
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(actor);
        Set<String> clear = req.clear() == null ? Set.of() : Set.copyOf(req.clear());

        String policy = secretsPolicy(req.secrets(), actor);
        if (req.title() != null) {
            t.title = guard(req.title().strip(), policy);
        }
        if (req.description() != null) {
            t.description = guard(req.description(), policy);
        }
        if (req.impact() != null) {
            t.impact = Level.fromCode(req.impact());
        }
        if (req.urgency() != null) {
            t.urgency = Level.fromCode(req.urgency());
        }
        if (req.priority() != null) {
            requireReason(req.priorityReason(), "priorityReason", "Укажите причину ручного приоритета");
            t.priority = Priority.fromCode(req.priority());
            t.priorityManual = true;
            ctx.reason = req.priorityReason();
        } else if (clear.contains("priority")) {
            t.priorityManual = false;
        }
        if (!t.priorityManual) {
            t.priority = settings.priorityMatrix().priority(t.impact, t.urgency);
        }
        if (req.groupId() != null || clear.contains("groupId")) {
            UUID group = clear.contains("groupId") ? null : req.groupId();
            if (!Objects.equals(group, t.groupId) && req.assigneeId() == null) {
                t.assigneeId = null; // исполнитель прежней группы не переходит в новую
            }
            t.groupId = group;
        }
        if (req.assigneeId() != null || clear.contains("assigneeId")) {
            t.assigneeId = clear.contains("assigneeId") ? null : req.assigneeId();
        }
        t.serviceId = pick(req.serviceId(), clear.contains("serviceId"), t.serviceId);
        t.ciId = pick(req.ciId(), clear.contains("ciId"), t.ciId);
        t.categoryId = pick(req.categoryId(), clear.contains("categoryId"), t.categoryId);
        t.createdForId = pick(req.createdForId(), clear.contains("createdForId"), t.createdForId);
        t.organizationId = pick(req.organizationId(), clear.contains("organizationId"), t.organizationId);
        t.securityGroupId = pick(req.securityGroupId(), clear.contains("securityGroupId"), t.securityGroupId);
        t.dueAt = clear.contains("dueAt") ? null : req.dueAt() != null ? req.dueAt() : t.dueAt;
        if (req.confidential() != null && req.confidential() != t.confidential) {
            if (req.confidential()) {
                requireReason(req.confidentialReason(), "confidentialReason", "Укажите причину ограничения доступа");
            }
            t.confidential = req.confidential();
            ctx.reason = req.confidentialReason();
        }
        if (!Objects.equals(before.categoryId, t.categoryId)) {
            t.identityCheckRequired = t.categoryId != null
                    && settings.general().identityCheckCategoryIds().contains(t.categoryId);
        }
        if (req.custom() != null) {
            t.custom = customFields.apply(t.type, t.custom, req.custom(), false);
        }
        access.requireValidAssignment(before, t, actor);
        if (t.groupId != null && !Objects.equals(before.groupId, t.groupId)) {
            engine.onGroupAssigned(t, ctx);
        }
        return engine.commit(before, t, ctx).ticket();
    }

    private static UUID pick(UUID value, boolean clear, UUID current) {
        return clear ? null : value != null ? value : current;
    }

    // ================================================================ переходы

    @Transactional
    TicketRow transition(String ref, String ifMatch, TicketRequests.Transition req, Actor actor) {
        if (actor.roles().equals(Set.of("AUD"))) {
            throw ApiException.forbidden();
        }
        TicketRow before = forUpdate(ref, actor, ifMatch);
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(actor);
        ctx.reasonCode = req.reasonCode();
        engine.transition(t, new TicketEngine.TransitionInput(req.to(), req.comment(), req.reasonCode(),
                req.resolutionCode(), req.resolutionText(), req.pendingUntil()), ctx);
        return engine.commit(before, t, ctx).ticket();
    }

    /** «Взять в работу»: исполнитель — я, статус категории in_progress. */
    @Transactional
    TicketRow take(String ref, String ifMatch, Actor actor) {
        access.requireSupport(actor);
        TicketRow before = access.requireWork(forUpdate(ref, actor, ifMatch), actor);
        return takeLocked(before, actor);
    }

    private TicketRow takeLocked(TicketRow before, Actor actor) {
        requireActive(before);
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(actor);
        t.assigneeId = actor.userId();
        if (t.statusCategory != StatusCategory.IN_PROGRESS) {
            Workflow wf = engine.workflow(t.type);
            Workflow.Transition tr = engine.available(t, actor).stream()
                    .filter(x -> wf.require(x.to()).categoryEnum() == StatusCategory.IN_PROGRESS)
                    .findFirst()
                    .orElseThrow(() -> TicketProblem.clash("transition_not_allowed",
                            "Заявку в этом статусе нельзя взять в работу"));
            engine.transition(t, TicketEngine.TransitionInput.to(tr.to()), ctx);
        }
        return engine.commit(before, t, ctx).ticket();
    }

    /**
     * «Взять следующую»: самая срочная неназначенная заявка групп, где состоит пользователь (или одной из них).
     * Чужая группа — 403, даже если её заявки видны (MIM): из чужой очереди берут конкретную заявку.
     */
    @Transactional
    Optional<TicketRow> takeNext(UUID groupId, Actor actor) {
        access.requireSupport(actor);
        Set<UUID> groups = access.myGroups(actor);
        if (groupId != null && !groups.contains(groupId)) {
            throw ApiException.forbidden();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("groups", Sql.array(groupId != null ? Set.of(groupId) : groups));
        String where = "where t.assignee_id is null and t.status_category in ('new', 'assigned')"
                + " and t.group_id = any(cast(:groups as uuid[])) and " + access.filter(actor, params);
        List<TicketRow> rows = store.query(where + " " + """
                 order by t.priority, t.sla_due_at nulls last, t.due_at nulls last, t.created_at, t.id
                 limit 1 for update of t skip locked""", params);
        return rows.stream().findFirst().map(t -> takeLocked(t, actor));
    }

    // ================================================================ эскалация, проверка личности, согласование

    @Override
    @Transactional
    public TicketRef escalate(UUID ticketId, EscalationKind kind, UUID targetGroupId, String reason, UUID actorId) {
        TicketRow t = escalate(ticketId.toString(), null, kind, targetGroupId, reason, Actor.system(actorId));
        return new TicketRef(t.id, t.number, t.status, t.statusCategory);
    }

    @Transactional
    TicketRow escalate(String ref, String ifMatch, EscalationKind kind, UUID groupId, String reason, Actor actor) {
        access.requireSupport(actor);
        TicketRow before = access.requireWork(forUpdate(ref, actor, ifMatch), actor);
        requireActive(before);
        requireReason(reason, "reason", "Укажите причину эскалации");
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(actor);
        ctx.reason = reason;
        if (kind == EscalationKind.FUNCTIONAL) {
            if (groupId == null) {
                throw TicketProblem.fieldRequired("groupId", "Укажите группу для функциональной эскалации");
            }
            if (!groupId.equals(t.groupId)) {
                access.requireValidAssignment(groupId, null, actor);
                t.groupId = groupId;
                t.assigneeId = routing.dispatch(groupId).orElse(null);
            }
        }
        t.escalationLevel = t.escalationLevel + 1;
        engine.onGroupAssigned(t, ctx);
        TicketRow saved = engine.commit(before, t, ctx).ticket();
        feed.add(saved.id, "system", "internal", actor.userId(), false, reason,
                Map.of("event", "escalated", "kind", kind.code(), "level", saved.escalationLevel), List.of(), ctx.now);
        events.publishEvent(new TicketEscalated(saved.tenantId, ctx.now, actor.userId(), saved.snapshot(), kind,
                saved.escalationLevel, before.groupId, reason));
        return saved;
    }

    /** INC-10: проверку личности отмечает поддержка, но не сам заявитель. */
    @Transactional
    TicketRow verifyIdentity(String ref, TicketRequests.IdentityCheck req, Actor actor) {
        TicketRow before = access.requireWork(resolve(ref, true), actor);
        requireActive(before);
        if (before.isRequesterSide(actor.userId())) {
            throw ApiException.forbidden();
        }
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(actor);
        t.identityVerifiedAt = ctx.now;
        t.identityVerifiedBy = actor.userId();
        t.identityMethod = req.method();
        TicketRow saved = engine.commit(before, t, ctx).ticket();
        feed.add(saved.id, "system", "internal", actor.userId(), false, req.note() == null ? "" : req.note(),
                Map.of("event", "identity_verified", "method", req.method()), List.of(), ctx.now);
        return saved;
    }

    @Override
    @Transactional
    public TicketRef applyApprovalOutcome(UUID ticketId, boolean approved, String reason) {
        TicketRow before = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        if (before.statusCategory != StatusCategory.APPROVAL) {
            throw TicketProblem.clash("transition_not_allowed", "Заявка не ожидает согласования");
        }
        if (!approved) {
            requireReason(reason, "reason", "Укажите причину отклонения");
        }
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(Actor.system(null)).trigger("approval");
        Workflow wf = engine.workflow(t.type);
        Workflow.Transition tr = wf.from(t.status).stream()
                .filter(x -> "approval".equals(x.trigger()))
                .filter(x -> (wf.require(x.to()).categoryEnum() == StatusCategory.CANCELLED) != approved)
                .findFirst()
                .orElseThrow(() -> TicketProblem.clash("transition_not_allowed", "В цикле нет перехода по итогу согласования"));
        engine.auto(t, "approval", new TicketEngine.TransitionInput(tr.to(), reason, approved ? null : "rejected", null,
                null, null), ctx);
        if (approved && t.groupId == null) {
            Routing.Result r = routing.route(t);
            t.groupId = r.groupId();
            t.assigneeId = r.assigneeId();
        }
        TicketRow saved = engine.commit(before, t, ctx).ticket();
        return new TicketRef(saved.id, saved.number, saved.status, saved.statusCategory);
    }

    @Override
    @Transactional
    public void updateSlaDue(UUID ticketId, Instant slaDueAt) {
        store.updateSlaDue(ticketId, slaDueAt); // неизвестная заявка — ничего не делает
    }

    // ================================================================ изменения от других модулей

    @Override
    @Transactional
    public TicketRef change(UUID ticketId, TicketChange c) {
        TicketRow before = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        requireActive(before);
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(Actor.system(null));
        ctx.reason = c.reason();
        if (c.impact() != null) {
            t.impact = c.impact();
        }
        if (c.urgency() != null) {
            t.urgency = c.urgency();
        }
        if (c.priority() != null) {
            t.priority = c.priority();
            t.priorityManual = true;
        } else if (!t.priorityManual) {
            t.priority = settings.priorityMatrix().priority(t.impact, t.urgency);
        }
        boolean groupChanged = c.groupId() != null && !c.groupId().equals(t.groupId);
        if (groupChanged) {
            t.groupId = c.groupId();
            t.assigneeId = c.assigneeId() != null ? c.assigneeId() : routing.dispatch(c.groupId()).orElse(null);
        } else if (c.assigneeId() != null) {
            t.assigneeId = c.assigneeId();
        }
        if (c.clearAssignee()) {
            t.assigneeId = null;
        }
        if (groupChanged) {
            engine.onGroupAssigned(t, ctx);
        }
        TicketRow saved = engine.commit(before, t, ctx).ticket();
        return new TicketRef(saved.id, saved.number, saved.status, saved.statusCategory);
    }

    @Override
    @Transactional
    public TicketRef transition(UUID ticketId, TicketTransition tr, String trigger) {
        TicketRow before = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(Actor.system(null)).trigger(trigger == null ? "system" : trigger);
        ctx.reasonCode = tr.reasonCode();
        engine.transition(t, new TicketEngine.TransitionInput(tr.to(), tr.comment(), tr.reasonCode(),
                tr.resolutionCode(), tr.resolutionText(), null), ctx);
        TicketRow saved = engine.commit(before, t, ctx).ticket();
        return new TicketRef(saved.id, saved.number, saved.status, saved.statusCategory);
    }

    @Override
    @Transactional
    public Optional<TicketRef> resolveIfPossible(UUID ticketId, String comment, String resolutionCode,
                                                 String resolutionText, String trigger) {
        TicketRow t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        if (t.statusCategory.isFinal() || t.statusCategory == StatusCategory.RESOLVED) {
            return Optional.empty();
        }
        Set<String> filled = new java.util.HashSet<>();
        if (comment != null && !comment.isBlank()) {
            filled.add("comment");
        }
        if (resolutionCode != null && !resolutionCode.isBlank() && resolutionText != null && !resolutionText.isBlank()) {
            filled.add("resolution");
        }
        if (t.groupId != null) {
            filled.add("group");
        }
        if (t.assigneeId != null) {
            filled.add("assignee");
        }
        Workflow wf = engine.workflow(t.type);
        Optional<String> to = wf.from(t.status).stream()
                .filter(Workflow.Transition::manual)
                .filter(x -> filled.containsAll(x.requiredFields()))
                .map(Workflow.Transition::to)
                .filter(code -> wf.status(code).map(s -> s.categoryEnum() == StatusCategory.RESOLVED).orElse(false))
                .findFirst();
        if (to.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(transition(ticketId, new TicketTransition(to.get(), comment, null, resolutionCode,
                resolutionText), trigger));
    }

    @Override
    @Transactional
    public boolean addWatcher(UUID ticketId, UUID userId, String reason) {
        TicketRow t = store.find(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        String why = Set.of("added", "mention", "creator").contains(reason) ? reason : "added";
        return watchers.add(t, userId, why, Actor.system(null));
    }

    // ================================================================ лента

    @Override
    @Transactional
    public UUID addComment(UUID ticketId, UUID authorId, String body, Visibility visibility) {
        TicketRow t = store.lock(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
        return comment(t, Actor.system(authorId), body, visibility, List.of(), "mask").id();
    }

    @Transactional
    Feed.Entry comment(String ref, TicketRequests.Comment req, Actor actor) {
        if (actor.roles().equals(Set.of("AUD"))) {
            throw ApiException.forbidden();
        }
        TicketRow t = access.requireRead(resolve(ref, true), actor);
        Visibility visibility = req.visibility() == null
                ? (actor.isSupport() ? Visibility.INTERNAL : Visibility.PUBLIC)
                : Visibility.fromCode(req.visibility());
        if (visibility == Visibility.INTERNAL && !actor.isSupport()) {
            throw ApiException.forbidden();
        }
        return comment(t, actor, req.body(), visibility, req.mentions() == null ? List.of() : req.mentions(),
                secretsPolicy(req.secrets(), actor));
    }

    private Feed.Entry comment(TicketRow before, Actor actor, String body, Visibility visibility, List<UUID> mentions,
                               String policy) {
        boolean isPublic = visibility == Visibility.PUBLIC;
        if (before.statusCategory.isFinal() && isPublic) {
            throw TicketProblem.clash("ticket_closed", "Заявка закрыта. Создайте новую связанную заявку");
        }
        String text = guard(body, policy);
        ChangeContext ctx = new ChangeContext(actor);
        boolean fromRequester = before.isRequesterSide(actor.userId()); // в т. ч. ответ заявителя письмом
        List<UUID> mentioned = mentions.stream().distinct().toList();
        UUID id = feed.add(before.id, isPublic ? "reply" : "note", visibility.code(), actor.userId(), fromRequester,
                text, Map.of(), mentioned, ctx.now);

        TicketRow t = before.copy();
        if (isPublic && fromRequester) {
            t.awaitingAgent = true;
            if (t.statusCategory == StatusCategory.RESOLVED || t.statusCategory == StatusCategory.PENDING) {
                ChangeContext auto = new ChangeContext(actor);
                if (engine.auto(t, "requester_reply", null, auto)) {
                    engine.commit(before, t, auto);
                    t = store.find(t.id).orElseThrow();
                    publishComment(t, ctx, actor, id, visibility, true, mentioned);
                    addMentions(t, mentioned, actor);
                    return entry(id, visibility, actor, fromRequester, text, mentioned, ctx);
                }
            }
        } else if (isPublic && actor.isSupport() && !actor.system()) {
            t.awaitingAgent = false;
            if (t.firstResponseAt == null) {
                t.firstResponseAt = ctx.now;
            }
        }
        if (t.awaitingAgent != before.awaitingAgent || !Objects.equals(t.firstResponseAt, before.firstResponseAt)) {
            store.touchService(t.id, t.firstResponseAt, t.awaitingAgent, ctx.now);
            if (before.firstResponseAt == null && t.firstResponseAt != null) {
                events.publishEvent(new ru.easyitsm.tickets.events.TicketFirstResponded(t.tenantId, t.firstResponseAt,
                        actor.userId(), t.snapshot(), "reply"));
            }
        }
        publishComment(t, ctx, actor, id, visibility, fromRequester, mentioned);
        addMentions(t, mentioned, actor);
        return entry(id, visibility, actor, fromRequester, text, mentioned, ctx);
    }

    private void publishComment(TicketRow t, ChangeContext ctx, Actor actor, UUID id, Visibility visibility,
                                boolean fromRequester, List<UUID> mentioned) {
        events.publishEvent(new TicketCommentAdded(t.tenantId, ctx.now, actor.userId(), t.snapshot(), id, visibility,
                fromRequester, mentioned));
    }

    private void addMentions(TicketRow t, List<UUID> mentioned, Actor actor) {
        for (UUID user : mentioned) {
            watchers.add(t, user, "mention", actor);
        }
    }

    private static Feed.Entry entry(UUID id, Visibility visibility, Actor actor, boolean fromRequester, String text,
                                    List<UUID> mentioned, ChangeContext ctx) {
        return new Feed.Entry(id, visibility == Visibility.PUBLIC ? "reply" : "note", visibility.code(), actor.userId(),
                fromRequester, text, Map.of(), mentioned, ctx.now);
    }

    // ================================================================ таймеры

    /** Срабатывание таймера в арендаторе заявки: автозакрытие (INC-07) или возврат из ожидания. */
    @Transactional
    boolean fireTimer(UUID ticketId, String timer) {
        Optional<TicketRow> row = store.lock(ticketId);
        if (row.isEmpty()) {
            return false;
        }
        TicketRow before = row.get();
        if ("autoclose".equals(timer)) {
            if (before.statusCategory != StatusCategory.RESOLVED || before.resolvedAt == null
                    || BusinessDays.add(before.resolvedAt, settings.general().autoCloseBusinessDays()).isAfter(Sql.now())) {
                return false;
            }
        } else if (before.statusCategory != StatusCategory.PENDING || before.pendingUntil == null
                || before.pendingUntil.isAfter(Sql.now())) {
            return false;
        }
        TicketRow t = before.copy();
        ChangeContext ctx = new ChangeContext(Actor.system(null));
        if (!engine.auto(t, "timer", null, ctx)) {
            return false;
        }
        engine.commit(before, t, ctx);
        return true;
    }

    // ================================================================ общее

    String secretsPolicy(String requested, Actor actor) {
        String p = requested == null ? "warn" : requested;
        if ("allow".equals(p) && !actor.isSupport()) {
            return "mask"; // заявитель не может сохранить секрет как есть
        }
        return p;
    }

    /** Применяет политику секретов к тексту: warn — ошибка с находками, mask — маскирование, allow — как есть. */
    String guard(String text, String policy) {
        if (text == null || "allow".equals(policy)) {
            return text;
        }
        List<SecretFinding> found = secrets.scan(text);
        if (found.isEmpty()) {
            return text;
        }
        if ("warn".equals(policy)) {
            throw TicketProblem.secrets(found);
        }
        return secrets.mask(text);
    }

    static void requireActive(TicketRow t) {
        if (t.statusCategory.isFinal()) {
            throw TicketProblem.clash("ticket_closed", "Заявка закрыта");
        }
    }

    static void requireReason(String reason, String field, String detail) {
        if (reason == null || reason.isBlank()) {
            throw TicketProblem.fieldRequired(field, detail);
        }
    }

    static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Недостаточно прав");
    }

    static <T> List<T> listOf(Iterable<T> items) {
        List<T> list = new ArrayList<>();
        items.forEach(list::add);
        return list;
    }
}
