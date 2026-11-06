package ru.easyitsm.tickets.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.events.TicketAssignmentChanged;
import ru.easyitsm.tickets.events.TicketFirstResponded;
import ru.easyitsm.tickets.events.TicketPriorityChanged;
import ru.easyitsm.tickets.events.TicketStatusChanged;
import ru.easyitsm.tickets.events.TicketUpdated;

/**
 * Ядро изменений заявки. Все изменения проходят через {@link #commit}: сохранение с проверкой версии,
 * история полей, запись в ленту и события считаются из разницы «до» и «после», поэтому их нельзя забыть.
 * Переходы проверяют права и обязательные поля по циклу типа (раздел 5).
 */
@Component
class TicketEngine {

    /** Отслеживаемые поля: имя в истории и REST → значение. */
    private static final Map<String, Function<TicketRow, Object>> TRACKED = new LinkedHashMap<>();
    /** Поля, которые меняются вместе со статусом и описываются записью о переходе. */
    private static final Set<String> STATUS_FIELDS = Set.of("status", "pendingReason", "pendingUntil", "resolutionCode",
            "resolutionText");
    private static final Set<String> ASSIGNMENT_FIELDS = Set.of("groupId", "assigneeId");

    static {
        TRACKED.put("title", t -> t.title);
        TRACKED.put("description", t -> t.description);
        TRACKED.put("status", t -> t.status);
        TRACKED.put("priority", t -> t.priority.code());
        TRACKED.put("impact", t -> t.impact.code());
        TRACKED.put("urgency", t -> t.urgency.code());
        TRACKED.put("groupId", t -> t.groupId);
        TRACKED.put("assigneeId", t -> t.assigneeId);
        TRACKED.put("serviceId", t -> t.serviceId);
        TRACKED.put("ciId", t -> t.ciId);
        TRACKED.put("categoryId", t -> t.categoryId);
        TRACKED.put("createdForId", t -> t.createdForId);
        TRACKED.put("organizationId", t -> t.organizationId);
        TRACKED.put("confidential", t -> t.confidential);
        TRACKED.put("securityGroupId", t -> t.securityGroupId);
        TRACKED.put("dueAt", t -> t.dueAt);
        TRACKED.put("parentId", t -> t.parentId);
        TRACKED.put("pendingReason", t -> t.pendingReason);
        TRACKED.put("pendingUntil", t -> t.pendingUntil);
        TRACKED.put("resolutionCode", t -> t.resolutionCode);
        TRACKED.put("resolutionText", t -> t.resolutionText);
        TRACKED.put("escalationLevel", t -> t.escalationLevel);
        TRACKED.put("identityVerifiedAt", t -> t.identityVerifiedAt);
    }

    private final TicketStore store;
    private final Feed feed;
    private final SettingsStore settings;
    private final ApplicationEventPublisher events;
    private final TicketAccess access;

    TicketEngine(TicketStore store, Feed feed, SettingsStore settings, ApplicationEventPublisher events,
                 TicketAccess access) {
        this.access = access;
        this.store = store;
        this.feed = feed;
        this.settings = settings;
        this.events = events;
    }

    record TransitionInput(String to, String comment, String reasonCode, String resolutionCode, String resolutionText,
                           java.time.Instant pendingUntil) {

        static TransitionInput to(String code) {
            return new TransitionInput(code, null, null, null, null, null);
        }
    }

    // ---------------------------------------------------------------- переходы

    Workflow workflow(TicketType type) {
        return settings.workflow(type);
    }

    /**
     * Может ли пользователь выполнить ручной переход (роли и псевдороли requester, assignee). Переход по роли
     * поддержки требует права работать с заявкой: агент чужой группы её не решает и не закрывает.
     */
    boolean allowed(Workflow.Transition tr, TicketRow t, Actor actor) {
        if (actor.system()) {
            return true;
        }
        for (String role : tr.roles()) {
            switch (role) {
                case "requester" -> {
                    if (t.isRequesterSide(actor.userId())) {
                        return true;
                    }
                }
                case "assignee" -> {
                    if (actor.is(t.assigneeId)) {
                        return true;
                    }
                }
                case "system" -> {
                }
                default -> {
                    if (actor.has(role) && (!Actor.SUPPORT_ROLES.contains(role) || access.canWork(t, actor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Ручные переходы из текущего статуса, доступные пользователю, без повторов целевого статуса. */
    List<Workflow.Transition> available(TicketRow t, Actor actor) {
        Workflow wf = workflow(t.type);
        Map<String, Workflow.Transition> byTarget = new LinkedHashMap<>();
        for (Workflow.Transition tr : wf.from(t.status)) {
            if (tr.manual() && allowed(tr, t, actor)) {
                byTarget.putIfAbsent(tr.to(), tr);
            }
        }
        return List.copyOf(byTarget.values());
    }

    /** Ручной переход по запросу пользователя: ищет разрешённый переход, проверяет поля, меняет строку. */
    void transition(TicketRow t, TransitionInput in, ChangeContext ctx) {
        Workflow wf = workflow(t.type);
        Workflow.Transition tr = wf.from(t.status).stream()
                .filter(x -> x.to().equals(in.to()) && x.manual() && allowed(x, t, ctx.actor))
                .findFirst()
                .orElseThrow(() -> TicketProblem.clash("transition_not_allowed",
                        "Переход в статус «" + wf.status(in.to()).map(Workflow.Status::name).orElse(in.to())
                                + "» недоступен"));
        apply(t, wf, tr, in, ctx);
    }

    /** Автоматический переход по триггеру, если он есть в цикле. Права не проверяются. */
    boolean auto(TicketRow t, String trigger, TransitionInput in, ChangeContext ctx) {
        Workflow wf = workflow(t.type);
        Optional<Workflow.Transition> tr = wf.from(t.status).stream()
                .filter(x -> x.trigger().equals(trigger) && (in == null || in.to() == null || x.to().equals(in.to())))
                .findFirst();
        if (tr.isEmpty()) {
            return false;
        }
        ctx.trigger(trigger);
        apply(t, wf, tr.get(), in == null ? TransitionInput.to(tr.get().to()) : in, ctx);
        return true;
    }

    /** Если у заявки появилась группа, а цикл умеет «Новая → Назначена» по триггеру, — выполняет его. */
    void onGroupAssigned(TicketRow t, ChangeContext ctx) {
        if (t.groupId != null && t.statusCategory == StatusCategory.NEW) {
            String keep = ctx.trigger;
            if (!auto(t, "group_assigned", null, ctx)) {
                ctx.trigger(keep);
            }
        }
    }

    private void apply(TicketRow t, Workflow wf, Workflow.Transition tr, TransitionInput in, ChangeContext ctx) {
        for (String f : tr.requiredFields()) {
            requireField(t, f, in, ctx);
        }
        Workflow.Status target = wf.require(tr.to());
        StatusCategory from = t.statusCategory;
        StatusCategory to = target.categoryEnum();
        if (to == StatusCategory.RESOLVED) {
            if (settings.general().resolveRequiresClosedSubtasks() && store.hasOpenSubtasks(t.id)) {
                throw TicketProblem.clash("open_subtasks", "Сначала завершите подзадачи");
            }
            if (t.identityCheckRequired && t.identityVerifiedAt == null) {
                throw TicketProblem.clash("identity_check_required",
                        "Перед решением отметьте проверку личности заявителя");
            }
        }
        t.status = target.code();
        t.statusCategory = to;
        if (from == StatusCategory.PENDING && to != StatusCategory.PENDING) {
            t.pendingReason = null;
            t.pendingUntil = null;
        }
        switch (to) {
            case IN_PROGRESS -> {
                if (t.firstResponseAt == null) {
                    t.firstResponseAt = ctx.now;
                    ctx.firstResponseVia = "status";
                }
                if (from == StatusCategory.RESOLVED) {
                    t.reopenCount++;
                    t.resolvedAt = null;
                    t.resolutionCode = null;
                    t.resolutionText = null;
                    if (ctx.reasonCode == null) {
                        ctx.reasonCode = "reopened";
                    }
                }
            }
            case PENDING -> {
                t.pendingReason = in.reasonCode();
                t.pendingUntil = in.pendingUntil();
                ctx.reasonCode = in.reasonCode();
            }
            case RESOLVED -> {
                if (in.resolutionCode() != null) {
                    t.resolutionCode = in.resolutionCode();
                    t.resolutionText = in.resolutionText();
                }
                t.resolvedAt = ctx.now;
                t.awaitingAgent = false;
            }
            case CLOSED -> {
                t.closedAt = ctx.now;
                t.awaitingAgent = false;
                if (in.resolutionCode() != null) {
                    t.resolutionCode = in.resolutionCode();
                    t.resolutionText = in.resolutionText();
                }
            }
            case CANCELLED -> {
                t.closedAt = ctx.now;
                t.awaitingAgent = false;
                t.resolutionCode = in.reasonCode() != null ? in.reasonCode()
                        : "rejected".equals(target.code()) ? "rejected" : "cancelled";
                t.resolutionText = in.comment();
                ctx.reasonCode = t.resolutionCode;
            }
            default -> {
            }
        }
        if (in.comment() != null && !in.comment().isBlank()) {
            ctx.comment = in.comment();
        }
    }

    private void requireField(TicketRow t, String field, TransitionInput in, ChangeContext ctx) {
        boolean ok = switch (field) {
            case "comment" -> notBlank(in.comment());
            case "reason_code" -> notBlank(in.reasonCode());
            case "resolution" -> notBlank(in.resolutionCode()) && notBlank(in.resolutionText());
            case "pending_until" -> in.pendingUntil() != null && in.pendingUntil().isAfter(ctx.now);
            case "group" -> t.groupId != null;
            case "assignee" -> t.assigneeId != null;
            default -> true;
        };
        if (!ok) {
            String detail = switch (field) {
                case "comment" -> "Добавьте комментарий";
                case "reason_code" -> "Укажите причину";
                case "resolution" -> "Укажите код и описание решения";
                case "pending_until" -> "Укажите срок возврата в будущем";
                case "group" -> "Назначьте группу";
                case "assignee" -> "Назначьте исполнителя";
                default -> "Заполните поле " + field;
            };
            throw TicketProblem.fieldRequired(field, detail);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---------------------------------------------------------------- сохранение

    record Committed(TicketRow ticket, List<Feed.Change> changes) {
    }

    /**
     * Сохраняет изменение. Ничего не изменилось — ничего не пишет. Иначе: версия +1, история, лента, события.
     */
    Committed commit(TicketRow before, TicketRow after, ChangeContext ctx) {
        List<Feed.Change> changes = diff(before, after, ctx);
        boolean serviceChanged = !Objects.equals(before.firstResponseAt, after.firstResponseAt)
                || before.awaitingAgent != after.awaitingAgent || before.reopenCount != after.reopenCount
                || before.identityCheckRequired != after.identityCheckRequired;
        if (changes.isEmpty() && !serviceChanged) {
            return new Committed(after, List.of());
        }
        after.updatedAt = ctx.now;
        after.updatedBy = ctx.actor.userId();
        after.version = before.version + 1;
        if (!store.update(after, before.version)) {
            throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
                    "Запись изменена другим пользователем. Обновите и повторите");
        }
        recordChange(before, after, changes, ctx);
        return new Committed(after, changes);
    }

    /** История, лента и события для созданной или изменённой заявки. */
    private void recordChange(TicketRow before, TicketRow after, List<Feed.Change> changes, ChangeContext ctx) {
        UUID actor = ctx.actor.userId();
        feed.history(after.id, changes);
        Set<String> fields = new HashSet<>();
        changes.forEach(c -> fields.add(c.field()));

        if (fields.contains("status")) {
            Workflow wf = workflow(after.type);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("event", "status_changed");
            meta.put("from", before.status);
            meta.put("to", after.status);
            meta.put("fromName", wf.status(before.status).map(Workflow.Status::name).orElse(before.status));
            meta.put("toName", wf.status(after.status).map(Workflow.Status::name).orElse(after.status));
            meta.put("trigger", ctx.trigger);
            if (ctx.reasonCode != null) {
                meta.put("reasonCode", ctx.reasonCode);
            }
            if (after.resolutionCode != null && after.statusCategory == StatusCategory.RESOLVED) {
                meta.put("resolutionCode", after.resolutionCode);
            }
            feed.add(after.id, "system", "public", actor, after.isRequesterSide(actor), ctx.comment == null ? "" : ctx.comment,
                    meta, List.of(), ctx.now);
            events.publishEvent(new TicketStatusChanged(after.tenantId, ctx.now, actor, after.snapshot(), before.status,
                    before.statusCategory, ctx.reasonCode, ctx.comment, ctx.trigger));
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Feed.Change c : changes) {
            if (STATUS_FIELDS.contains(c.field()) || c.field().equals("identityVerifiedAt")) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", c.field());
            boolean longText = c.field().equals("description");
            m.put("from", longText ? null : c.from());
            m.put("to", longText ? null : c.to());
            if (c.reason() != null) {
                m.put("reason", c.reason());
            }
            visible.add(m);
        }
        if (!visible.isEmpty()) {
            feed.add(after.id, "change", "internal", actor, false, "", Map.of("changes", visible), List.of(), ctx.now);
        }
        if (fields.contains("priority")) {
            events.publishEvent(new TicketPriorityChanged(after.tenantId, ctx.now, actor, after.snapshot(),
                    before.priority, after.priorityManual, ctx.reason));
        }
        if (fields.stream().anyMatch(ASSIGNMENT_FIELDS::contains)) {
            events.publishEvent(new TicketAssignmentChanged(after.tenantId, ctx.now, actor, after.snapshot(),
                    before.groupId, before.assigneeId));
        }
        Set<String> other = new java.util.TreeSet<>(fields);
        other.removeAll(STATUS_FIELDS);
        other.removeAll(ASSIGNMENT_FIELDS);
        other.remove("priority");
        other.remove("identityVerifiedAt");
        if (!other.isEmpty()) {
            events.publishEvent(new TicketUpdated(after.tenantId, ctx.now, actor, after.snapshot(), Set.copyOf(other)));
        }
        if (before.firstResponseAt == null && after.firstResponseAt != null) {
            events.publishEvent(new TicketFirstResponded(after.tenantId, after.firstResponseAt, actor, after.snapshot(),
                    ctx.firstResponseVia == null ? "status" : ctx.firstResponseVia));
        }
    }

    private static List<Feed.Change> diff(TicketRow before, TicketRow after, ChangeContext ctx) {
        List<Feed.Change> changes = new ArrayList<>();
        for (var e : TRACKED.entrySet()) {
            Object a = e.getValue().apply(before);
            Object b = e.getValue().apply(after);
            if (!Objects.equals(a, b)) {
                String reason = switch (e.getKey()) {
                    case "status" -> ctx.reasonCode;
                    case "priority", "confidential", "escalationLevel" -> ctx.reason;
                    default -> null;
                };
                changes.add(new Feed.Change(e.getKey(), plain(a), plain(b), ctx.actor.userId(), reason, ctx.now));
            }
        }
        Set<String> keys = new java.util.TreeSet<>(before.custom.keySet());
        keys.addAll(after.custom.keySet());
        for (String k : keys) {
            Object a = before.custom.get(k);
            Object b = after.custom.get(k);
            if (!Objects.equals(a, b)) {
                changes.add(new Feed.Change("custom." + k, a, b, ctx.actor.userId(), null, ctx.now));
            }
        }
        return changes;
    }

    private static Object plain(Object v) {
        return v instanceof UUID || v instanceof java.time.Instant ? v.toString() : v;
    }
}
