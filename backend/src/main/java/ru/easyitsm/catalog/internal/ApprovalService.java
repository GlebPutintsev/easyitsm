package ru.easyitsm.catalog.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.catalog.api.ApprovalQueries;
import ru.easyitsm.catalog.events.ApprovalDecided;
import ru.easyitsm.catalog.events.ApprovalInfoRequested;
import ru.easyitsm.catalog.events.ApprovalRequested;
import ru.easyitsm.catalog.internal.form.FormSchemaValidator;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * Многошаговое согласование (REQ-03). Шаги идут последовательно, внутри шага — все, любой или кворум.
 * Автор заявки не согласует её сам: он исключается из согласующих при заказе.
 */
@Service
class ApprovalService implements ApprovalQueries {

    private final ApprovalFlowRepository flows;
    private final ApprovalRequestRepository requests;
    private final ApprovalRepository approvals;
    private final TicketGateway tickets;
    private final JsonCodec json;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    ApprovalService(ApprovalFlowRepository flows, ApprovalRequestRepository requests, ApprovalRepository approvals,
                    TicketGateway tickets, JsonCodec json, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.flows = flows;
        this.requests = requests;
        this.approvals = approvals;
        this.tickets = tickets;
        this.json = json;
        this.jdbc = jdbc;
        this.events = events;
    }

    // ---------- шаблоны маршрутов ----------

    @Transactional(readOnly = true)
    public List<CatalogViews.FlowView> flows() {
        return flows.findAllByOrderByNameAsc().stream().map(f -> f.toView(json)).toList();
    }

    @Transactional(readOnly = true)
    public CatalogViews.FlowView flow(UUID id) {
        return flows.findById(id).orElseThrow(() -> ApiException.notFound("Маршрут")).toView(json);
    }

    @Transactional
    public CatalogViews.FlowView createFlow(CatalogCommands.FlowInput in) {
        var f = new ApprovalFlow(in.name());
        f.update(in.name(), in.description(), stepsJson(in), in.active() == null || in.active());
        return flows.save(f).toView(json);
    }

    @Transactional
    public CatalogViews.FlowView updateFlow(UUID id, String ifMatch, CatalogCommands.FlowInput in) {
        ApprovalFlow f = flows.findById(id).orElseThrow(() -> ApiException.notFound("Маршрут"));
        ETags.requireMatch(ifMatch, f.getVersion());
        f.update(in.name(), in.description(), stepsJson(in), in.active() == null || in.active());
        return flows.save(f).toView(json);
    }

    private String stepsJson(CatalogCommands.FlowInput in) {
        List<StepDef> steps = new ArrayList<>();
        for (var s : in.steps()) {
            if (s.mode().equals("quorum") && (s.quorum() == null || s.quorum() > s.approvers().size())) {
                throw ApiException.badRequest("invalid_quorum",
                        "Кворум должен быть от 1 до числа согласующих (шаг " + s.name() + ")");
            }
            if (s.approvers().stream().distinct().count() != s.approvers().size()) {
                throw ApiException.badRequest("duplicate_approver", "Согласующий повторяется (шаг " + s.name() + ")");
            }
            FormSchemaValidator.checkStepCondition(s.condition(), s.name());
            steps.add(new StepDef(s.name(), s.mode(), s.mode().equals("quorum") ? s.quorum() : null,
                    List.copyOf(s.approvers()), s.condition(), s.dueMinutes(), s.allowDelegation()));
        }
        return json.write(steps);
    }

    // ---------- запуск маршрута ----------

    /**
     * Шаги маршрута для заказа: только те, чьё условие выполнено, без автора среди согласующих.
     * Шаг, где кроме автора согласовать некому, — ошибка настройки: молча пропускать его нельзя.
     */
    @Transactional(readOnly = true)
    public List<StepDef> plan(UUID flowId, Map<String, Object> values, UUID requester) {
        if (flowId == null) {
            return List.of();
        }
        ApprovalFlow flow = flows.findById(flowId).filter(ApprovalFlow::active).orElse(null);
        if (flow == null) {
            return List.of();
        }
        List<StepDef> route = new ArrayList<>();
        for (StepDef step : json.steps(flow.steps())) {
            if (step.condition() != null && !step.condition().test(values)) {
                continue;
            }
            List<UUID> others = step.approvers().stream().filter(a -> !a.equals(requester)).toList();
            if (others.isEmpty()) {
                throw ApiException.conflict("no_approver",
                        "Для шага «" + step.name() + "» нет согласующего, кроме вас. Обратитесь к администратору каталога");
            }
            route.add(step.withApprovers(others));
        }
        return route;
    }

    @Transactional
    public void start(TicketGateway.TicketRef ticket, String title, UUID itemId, UUID flowId, List<StepDef> route,
                      UUID requester, UUID createdFor) {
        var request = requests.save(new ApprovalRequest(ticket.id(), ticket.number(), title, itemId, flowId, requester,
                createdFor, json.write(route)));
        open(request, 0, route.get(0));
    }

    private void open(ApprovalRequest request, int index, StepDef step) {
        request.moveTo(index);
        requests.save(request);
        Instant due = step.dueMinutes() == null ? null : Instant.now().plus(Duration.ofMinutes(step.dueMinutes()));
        for (UUID approver : step.approvers()) {
            assign(request, index, step.name(), approver, null, due);
        }
    }

    private void assign(ApprovalRequest request, int index, String stepName, UUID approver, UUID delegateFrom,
                        Instant due) {
        Approval a = approvals.save(new Approval(request.getId(), request.ticketId(), index, stepName, approver,
                delegateFrom, due));
        events.publishEvent(new ApprovalRequested(TenantContext.require(), a.getId(), request.ticketId(),
                request.ticketNumber(), request.title(), approver, stepName, due));
    }

    // ---------- решения ----------

    @Transactional
    public CatalogViews.ApprovalView decide(UUID approvalId, CatalogCommands.Decision cmd) {
        UUID me = Roles.actor();
        Approval a = mine(approvalId, me);
        ApprovalRequest request = requests.findById(a.requestId()).orElseThrow();
        requireOpen(a, request);
        String comment = cmd.comment() == null || cmd.comment().isBlank() ? null : cmd.comment().strip();

        switch (cmd.decision()) {
            case "approve" -> a.decide("approved", comment, "session");
            case "reject" -> {
                if (comment == null) {
                    throw ApiException.badRequest("reason_required", "Укажите причину отклонения");
                }
                a.decide("rejected", comment, "session");
            }
            default -> {
                if (comment == null) {
                    throw ApiException.badRequest("question_required", "Напишите, что нужно уточнить");
                }
                a.requestInfo(comment);
                approvals.save(a);
                events.publishEvent(new ApprovalInfoRequested(TenantContext.require(), a.getId(), a.ticketId(),
                        request.ticketNumber(), me, request.requesterId(), comment));
                return view(a, request);
            }
        }
        approvals.save(a);
        evaluate(request, me);
        return view(approvals.findById(a.getId()).orElseThrow(), requests.findById(request.getId()).orElseThrow());
    }

    @Transactional
    public CatalogViews.ApprovalView delegate(UUID approvalId, CatalogCommands.Delegate cmd) {
        UUID me = Roles.actor();
        Approval a = mine(approvalId, me);
        ApprovalRequest request = requests.findById(a.requestId()).orElseThrow();
        requireOpen(a, request);
        StepDef step = json.steps(request.steps()).get(a.stepIndex());
        if (!step.allowDelegation()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "delegation_not_allowed",
                    "На этом шаге передавать согласование нельзя");
        }
        UUID to = cmd.toUserId();
        if (to.equals(me) || to.equals(request.requesterId())) {
            throw ApiException.badRequest("invalid_delegate", "Передать можно другому сотруднику, кроме автора заявки");
        }
        boolean alreadyThere = approvals.findByRequestIdAndStepIndex(request.getId(), a.stepIndex()).stream()
                .anyMatch(x -> x.pending() && x.approverId().equals(to));
        if (alreadyThere) {
            throw ApiException.conflict("already_approver", "Сотрудник уже согласует этот шаг");
        }
        a.decide("delegated", cmd.comment(), "session");
        approvals.save(a);
        assign(request, a.stepIndex(), a.stepName(), to, me, a.dueAt());
        return view(a, request);
    }

    /** Отмена заявки закрывает её согласования без решения. Вызывается по событию модуля tickets. */
    @Transactional
    public void cancelForTicket(UUID ticketId) {
        requests.findByTicketId(ticketId).filter(ApprovalRequest::pending).ifPresent(r -> {
            approvals.findByRequestIdOrderByStepIndexAscCreatedAtAsc(r.getId()).stream()
                    .filter(Approval::pending)
                    .forEach(a -> {
                        a.close("cancelled");
                        approvals.save(a);
                    });
            r.finish("cancelled");
            requests.save(r);
        });
    }

    private void evaluate(ApprovalRequest request, UUID decidedBy) {
        List<StepDef> steps = json.steps(request.steps());
        int index = request.currentStep();
        StepDef step = steps.get(index);
        List<Approval> current = approvals.findByRequestIdAndStepIndex(request.getId(), index).stream()
                .filter(a -> !a.decision().equals("delegated") && !a.decision().equals("cancelled"))
                .toList();
        long approved = current.stream().filter(a -> a.decision().equals("approved")).count();
        long pending = current.stream().filter(Approval::pending).count();
        int required = step.required(current.size());

        if (approved >= required) {
            skipPending(current);
            if (index + 1 < steps.size()) {
                open(request, index + 1, steps.get(index + 1));
            } else {
                finish(request, true, decidedBy, null);
            }
        } else if (approved + pending < required) {
            skipPending(current);
            String reason = current.stream().filter(a -> a.decision().equals("rejected"))
                    .max(Comparator.comparing(Approval::decidedAt)).map(Approval::comment).orElse(null);
            finish(request, false, decidedBy, reason);
        }
    }

    private void skipPending(List<Approval> step) {
        step.stream().filter(Approval::pending).forEach(a -> {
            a.close("skipped");
            approvals.save(a);
        });
    }

    private void finish(ApprovalRequest request, boolean approved, UUID decidedBy, String reason) {
        request.finish(approved ? "approved" : "rejected");
        requests.save(request);
        tickets.applyApprovalOutcome(request.ticketId(), approved, reason);
        events.publishEvent(new ApprovalDecided(TenantContext.require(), request.ticketId(), request.ticketNumber(),
                approved ? "approved" : "rejected", decidedBy, request.requesterId(), reason));
    }

    /** Чужое согласование выглядит как отсутствующее (AT-01). */
    private Approval mine(UUID approvalId, UUID me) {
        return approvals.findById(approvalId).filter(a -> a.approverId().equals(me))
                .orElseThrow(() -> ApiException.notFound("Согласование"));
    }

    private static void requireOpen(Approval a, ApprovalRequest request) {
        if (!a.pending() || !request.pending()) {
            throw ApiException.conflict("already_decided", "Решение по согласованию уже принято");
        }
    }

    // ---------- чтение ----------

    @Override
    @Transactional(readOnly = true)
    public long pendingCount(UUID approverId) {
        return approvals.pendingCount(approverId);
    }

    /** Мои согласования (10.8): pending — ждут меня, decided — мои решения. */
    @Transactional(readOnly = true)
    public CursorPage<CatalogViews.ApprovalView> mine(String tab, PageParams page) {
        UUID me = Roles.actor();
        KeysetCursor after = page.keyset();
        String filter = "decided".equals(tab)
                ? "a.decision in ('approved', 'rejected', 'delegated')"
                : "a.decision = 'pending' and r.state = 'pending'";
        List<Row> rows = jdbc.sql("""
                        select a.id, a.ticket_id, r.ticket_number, r.title, r.requester_id, r.created_for_id,
                               a.step_name, a.step_index, a.decision, a.comment, a.info_requested_at, a.due_at,
                               a.decided_at, a.created_at, a.delegate_from, r.steps
                          from approval a join approval_request r on r.id = a.request_id
                         where a.approver_id = :me and """ + filter + """
                           and (cast(:afterAt as timestamptz) is null
                                or (a.created_at, a.id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid)))
                         order by a.created_at desc, a.id desc
                         limit :limit""")
                .param("me", me)
                .param("afterAt", after == null ? null : java.sql.Timestamp.from(Instant.parse(after.sortValue())))
                .param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new Row(new CatalogViews.ApprovalView(
                        rs.getObject("id", UUID.class), rs.getObject("ticket_id", UUID.class),
                        rs.getString("ticket_number"), rs.getString("title"),
                        rs.getObject("requester_id", UUID.class), rs.getObject("created_for_id", UUID.class),
                        rs.getString("step_name"), rs.getInt("step_index"), rs.getString("decision"),
                        rs.getString("comment"), instant(rs, "info_requested_at"), instant(rs, "due_at"),
                        instant(rs, "decided_at"), instant(rs, "created_at"), rs.getObject("delegate_from", UUID.class),
                        json.steps(rs.getString("steps")).get(rs.getInt("step_index")).allowDelegation())))
                .list();
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.view().createdAt().toString(), r.view().id()))
                .map(Row::view);
    }

    private record Row(CatalogViews.ApprovalView view) {
    }

    /**
     * Маршрут согласования заявки для карточки. Видят автор, сотрудник, для которого создана заявка,
     * согласующие и роли обработки; остальным — 404.
     */
    @Transactional(readOnly = true)
    public CatalogViews.RouteView route(UUID ticketId) {
        UUID me = Roles.actor();
        ApprovalRequest request = requests.findByTicketId(ticketId)
                .orElseThrow(() -> ApiException.notFound("Маршрут согласования"));
        List<Approval> all = approvals.findByRequestIdOrderByStepIndexAscCreatedAtAsc(request.getId());
        boolean allowed = me.equals(request.requesterId()) || me.equals(request.createdForId())
                || all.stream().anyMatch(a -> a.approverId().equals(me))
                || Roles.current().stream().anyMatch(Roles.ROUTE_READERS::contains);
        if (!allowed) {
            throw ApiException.notFound("Маршрут согласования");
        }
        Map<Integer, List<Approval>> byStep = all.stream().collect(Collectors.groupingBy(Approval::stepIndex));
        List<StepDef> steps = json.steps(request.steps());
        List<CatalogViews.RouteStep> out = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            StepDef s = steps.get(i);
            List<Approval> list = byStep.getOrDefault(i, List.of());
            List<Approval> counted = list.stream()
                    .filter(a -> !a.decision().equals("delegated") && !a.decision().equals("cancelled")).toList();
            out.add(new CatalogViews.RouteStep(i, s.name(), s.mode(), s.required(Math.max(counted.size(),
                    s.approvers().size())), stepState(request, i, counted),
                    list.stream().map(a -> new CatalogViews.RouteApproval(a.getId(), a.approverId(), a.decision(),
                            a.comment(), a.decidedAt(), a.delegateFrom())).toList()));
        }
        return new CatalogViews.RouteView(ticketId, request.state(), request.currentStep(), request.decidedAt(), out);
    }

    private static String stepState(ApprovalRequest r, int i, List<Approval> approvals) {
        if (i > r.currentStep()) {
            return r.pending() ? "waiting" : "skipped";
        }
        if (i < r.currentStep()) {
            return "approved";
        }
        return switch (r.state()) {
            case "approved" -> "approved";
            case "rejected" -> approvals.stream().anyMatch(a -> a.decision().equals("rejected")) ? "rejected" : "skipped";
            case "cancelled" -> "cancelled";
            default -> "pending";
        };
    }

    private CatalogViews.ApprovalView view(Approval a, ApprovalRequest r) {
        StepDef step = json.steps(r.steps()).get(a.stepIndex());
        return new CatalogViews.ApprovalView(a.getId(), a.ticketId(), r.ticketNumber(), r.title(), r.requesterId(),
                r.createdForId(), a.stepName(), a.stepIndex(), a.decision(), a.comment(), a.infoRequestedAt(),
                a.dueAt(), a.decidedAt(), a.getCreatedAt(), a.delegateFrom(), step.allowDelegation());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
