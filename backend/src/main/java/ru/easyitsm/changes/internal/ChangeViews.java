package ru.easyitsm.changes.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.ChangeType;

/** Собирает {@link ChangeDtos.ChangeView}: шаги со временем, согласования, CAB, недостающие поля и права. */
@Component
class ChangeViews {

    private final ChangeStore store;
    private final ChangeAccess access;
    private final CabStore cab;
    private final ConfigurationItems cmdb;
    private final ChangeSettings settings;

    ChangeViews(ChangeStore store, ChangeAccess access, CabStore cab, ConfigurationItems cmdb,
                ChangeSettings settings) {
        this.store = store;
        this.access = access;
        this.cab = cab;
        this.cmdb = cmdb;
        this.settings = settings;
    }

    ChangeDtos.ChangeView view(ChangeRow c, Me me) {
        List<ChangeStore.Step> steps = store.steps(c.id);
        List<ChangeStore.Approval> approvals = store.approvals(c.id);
        return view(c, me, steps, approvals);
    }

    ChangeDtos.ChangeView view(ChangeRow c, Me me, List<ChangeStore.Step> steps, List<ChangeStore.Approval> approvals) {
        PlanSchedule.Result plan = PlanSchedule.compute(c, steps);
        boolean work = access.canWork(c, me);
        List<ChangeDtos.ApprovalView> approvalViews = approvals.stream()
                .map(a -> new ChangeDtos.ApprovalView(a.id(), a.kind(), a.groupId(), a.approverId(), a.round(),
                        a.postFactum(), a.decision(), a.comment(), a.decidedBy(), a.decidedAt(), a.dueAt(),
                        a.pending() && !"cab".equals(a.kind()) && !me.is(c.authorId) && access.isApprover(a, me)
                                && (a.postFactum() || c.state == ChangeState.APPROVAL)))
                .toList();
        List<ChangeDtos.CabEntry> cabEntries = new ArrayList<>();
        Map<UUID, CabStore.Meeting> meetings = new HashMap<>();
        for (CabStore.Item i : cab.itemsOfChange(c.id)) {
            CabStore.Meeting m = meetings.computeIfAbsent(i.meetingId(), id -> cab.find(id).orElse(null));
            if (m != null) {
                cabEntries.add(new ChangeDtos.CabEntry(m.id(), m.title(), m.startsAt(), m.state(), i.id(),
                        i.decision(), i.comment()));
            }
        }
        List<ChangeDtos.CiView> cis = cmdb.find(c.ciIds).stream()
                .map(ci -> new ChangeDtos.CiView(ci.id(), ci.name(), ci.className(), ci.service())).toList();
        String computed = null;
        if (!c.riskAnswers.isEmpty()) {
            try {
                computed = RiskModel.evaluate(c.riskAnswers).level().code();
            } catch (ChangeProblem e) {
                computed = null;
            }
        }
        var risk = new ChangeDtos.RiskView(c.riskAnswers, c.riskScore, c.riskLevel == null ? null : c.riskLevel.code(),
                c.riskManual, c.riskReason, c.riskAssessedAt, c.riskAssessedBy, computed);
        boolean overdue = c.postApprovalDueAt != null && Instant.now().isAfter(c.postApprovalDueAt)
                && approvals.stream().anyMatch(a -> a.postFactum() && a.pending());
        return new ChangeDtos.ChangeView(c.id, c.number, c.title, c.type.code(), c.state.code(), c.templateId,
                c.sourceTicketId, c.authorId, c.groupId, c.assigneeId, c.confidential, c.goal, c.justification,
                c.impact, c.risks, c.impactRows, c.systemCriticality, c.windowStart, c.windowEnd, c.timeZone,
                c.prepStart, c.downtimeMinutes, c.ciIds, c.serviceIds, cis, risk, c.conflictsAck, c.submittedAt,
                c.approvedAt, c.postApprovalDueAt, overdue, c.actualStart, c.actualEnd, c.rollbackStartedAt,
                c.outcome == null ? null : c.outcome.code(), c.conclusion, c.review, c.closedAt, c.cancelReason,
                c.pipeline, plan.steps(), plan.totals(), approvalViews, cabEntries, missing(c, steps),
                permissions(c, me, work, steps), c.createdAt, c.updatedAt, c.version);
    }

    /**
     * Недостающее для следующего шага цикла (CHG-02): коды полей {@code goal}, {@code justification},
     * {@code impact}, {@code window}, {@code downtime}, {@code affected}, {@code plan.implement},
     * {@code plan.verify}, {@code plan.rollback}, {@code risk}.
     */
    List<String> missing(ChangeRow c, List<ChangeStore.Step> steps) {
        List<String> m = new ArrayList<>();
        if (blank(c.goal)) {
            m.add("goal");
        }
        if (blank(c.justification)) {
            m.add("justification");
        }
        if (c.type != ChangeType.EMERGENCY && blank(c.impact)) {
            m.add("impact");
        }
        if (c.windowStart == null || c.windowEnd == null) {
            m.add("window");
        }
        if (c.downtimeMinutes == null) {
            m.add("downtime");
        }
        if (settings.get().requireAffected() && c.ciIds.isEmpty() && c.serviceIds.isEmpty()) {
            m.add("affected");
        }
        if (steps.stream().noneMatch(s -> "implement".equals(s.phase()))) {
            m.add("plan.implement");
        }
        if (c.type != ChangeType.EMERGENCY && steps.stream().noneMatch(s -> "verify".equals(s.phase()))) {
            m.add("plan.verify");
        }
        if (steps.stream().noneMatch(s -> "rollback".equals(s.phase()))) {
            m.add("plan.rollback");
        }
        if (c.type == ChangeType.NORMAL && c.state == ChangeState.ASSESSMENT && c.riskLevel == null) {
            m.add("risk");
        }
        return m;
    }

    private static ChangeDtos.Permissions permissions(ChangeRow c, Me me, boolean work, List<ChangeStore.Step> steps) {
        ChangeState s = c.state;
        boolean editable = work && (s == ChangeState.DRAFT || s == ChangeState.ASSESSMENT);
        boolean inWork = s == ChangeState.IMPLEMENTATION || s == ChangeState.VERIFICATION;
        return new ChangeDtos.Permissions(
                editable,
                editable,
                work && s == ChangeState.DRAFT,
                editable && c.type != ChangeType.STANDARD,
                me.manager() && s == ChangeState.ASSESSMENT,
                work && s == ChangeState.ASSESSMENT,
                work && (s == ChangeState.APPROVAL || s == ChangeState.SCHEDULED),
                work && s == ChangeState.SCHEDULED,
                work && inWork,
                work && inWork && c.rollbackStartedAt == null && steps.stream().anyMatch(x -> "rollback".equals(x.phase())),
                work && inWork,
                me.manager() && s == ChangeState.REVIEW,
                s.beforeImplementation() && (me.manager() || me.is(c.authorId)));
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
