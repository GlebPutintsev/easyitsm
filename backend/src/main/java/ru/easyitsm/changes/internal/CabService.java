package ru.easyitsm.changes.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.identity.api.AccessDecisions;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * CAB (CHG-07, 10.10): «Собрать повестку», «Начать заседание», голосование участников, решения «Утвердить»,
 * «Отклонить», «Отложить» и «Разослать протокол». Решение утвердить или отклонить закрывает слот CAB в маршруте
 * согласования изменения; отложенное изменение попадёт в повестку следующего заседания.
 */
@Service
class CabService {

    private final CabStore cab;
    private final ChangeStore changes;
    private final ChangeService changeService;
    private final AccessDecisions decisions;
    private final UserDirectory directory;
    private final Notifier notifier;

    CabService(CabStore cab, ChangeStore changes, ChangeService changeService, AccessDecisions decisions,
               UserDirectory directory, Notifier notifier) {
        this.cab = cab;
        this.changes = changes;
        this.changeService = changeService;
        this.decisions = decisions;
        this.directory = directory;
        this.notifier = notifier;
    }

    record MeetingInput(String title, Instant startsAt, Integer durationMinutes, String location, UUID chairId,
                        List<UUID> memberIds, String minutes) {
    }

    record VoteView(UUID voterId, String vote, String comment, Instant votedAt) {
    }

    record ItemView(UUID id, int seq, ChangeDtos.ChangeItem change, String decision, String comment, UUID decidedBy,
                    Instant decidedAt, List<VoteView> votes, Map<String, Long> tally, String myVote, boolean canVote,
                    boolean canDecide) {
    }

    record MeetingPermissions(boolean manage, boolean start, boolean complete, boolean sendProtocol) {
    }

    record MeetingView(UUID id, String title, Instant startsAt, int durationMinutes, String location, UUID chairId,
                       List<UUID> memberIds, String state, String minutes, Instant startedAt, Instant completedAt,
                       Instant protocolSentAt, List<ItemView> items, MeetingPermissions permissions, long version) {
    }

    record MeetingItem(UUID id, String title, Instant startsAt, int durationMinutes, String location, UUID chairId,
                       String state, long items) {
    }

    // ---------------------------------------------------------------- чтение

    @Transactional(readOnly = true)
    CursorPage<MeetingItem> list(String state, PageParams page) {
        KeysetCursor after = page.keyset();
        List<CabStore.Meeting> rows = cab.page(after == null ? null : Instant.parse(after.sortValue()),
                after == null ? null : after.id(), state, page.limit() + 1);
        return CursorPage.of(rows, page.limit(), m -> new KeysetCursor(m.startsAt().toString(), m.id()))
                .map(m -> new MeetingItem(m.id(), m.title(), m.startsAt(), m.durationMinutes(), m.location(),
                        m.chairId(), m.state(), cab.items(m.id()).size()));
    }

    @Transactional(readOnly = true)
    MeetingView get(UUID id, Me me) {
        return view(meeting(id), me);
    }

    private CabStore.Meeting meeting(UUID id) {
        return cab.find(id).orElseThrow(() -> ApiException.notFound("Заседание CAB"));
    }

    MeetingView view(CabStore.Meeting m, Me me) {
        boolean manage = me.manager() || me.is(m.chairId());
        boolean participant = m.participant(me.userId());
        Map<UUID, List<CabStore.Vote>> votes = cab.votes(m.id()).stream()
                .collect(Collectors.groupingBy(CabStore.Vote::itemId));
        List<ItemView> items = new ArrayList<>();
        for (CabStore.Item i : cab.items(m.id())) {
            ChangeRow c = changes.find(i.changeId()).orElse(null);
            if (c == null) {
                continue;
            }
            List<CabStore.Vote> vs = votes.getOrDefault(i.id(), List.of());
            Map<String, Long> tally = vs.stream().collect(Collectors.groupingBy(CabStore.Vote::vote, Collectors.counting()));
            String mine = vs.stream().filter(v -> me.is(v.voterId())).map(CabStore.Vote::vote).findFirst().orElse(null);
            boolean open = "pending".equals(i.decision());
            boolean notAuthor = !me.is(c.authorId);
            items.add(new ItemView(i.id(), i.seq(), ChangeDtos.ChangeItem.of(c), i.decision(), i.comment(),
                    i.decidedBy(), i.decidedAt(),
                    vs.stream().map(v -> new VoteView(v.voterId(), v.vote(), v.comment(), v.votedAt())).toList(),
                    tally, mine, open && participant && notAuthor && !"completed".equals(m.state())
                            && !"cancelled".equals(m.state()),
                    open && manage && notAuthor && "in_progress".equals(m.state())));
        }
        return new MeetingView(m.id(), m.title(), m.startsAt(), m.durationMinutes(), m.location(), m.chairId(),
                m.memberIds(), m.state(), m.minutes(), m.startedAt(), m.completedAt(), m.protocolSentAt(), items,
                new MeetingPermissions(manage && ("planned".equals(m.state()) || "in_progress".equals(m.state())),
                        manage && "planned".equals(m.state()), manage && "in_progress".equals(m.state()),
                        manage && "completed".equals(m.state())),
                m.version());
    }

    // ---------------------------------------------------------------- заседание

    @Transactional
    MeetingView create(MeetingInput in, Me me) {
        if (in.title() == null || in.title().isBlank()) {
            throw ChangeProblem.field("title", "Укажите тему заседания");
        }
        if (in.startsAt() == null) {
            throw ChangeProblem.field("startsAt", "Укажите время заседания");
        }
        UUID chair = in.chairId() != null ? in.chairId() : me.userId();
        List<UUID> members = members(in.memberIds(), chair);
        UUID id = cab.insert(in.title().strip(), in.startsAt(), duration(in.durationMinutes()), blank(in.location()),
                chair, members);
        return view(meeting(id), me);
    }

    @Transactional
    MeetingView update(UUID id, String ifMatch, MeetingInput in, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        if ("completed".equals(m.state()) || "cancelled".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Заседание завершено");
        }
        UUID chair = in.chairId() != null ? in.chairId() : m.chairId();
        CabStore.Meeting next = new CabStore.Meeting(m.id(),
                in.title() != null && !in.title().isBlank() ? in.title().strip() : m.title(),
                in.startsAt() != null ? in.startsAt() : m.startsAt(),
                in.durationMinutes() != null ? duration(in.durationMinutes()) : m.durationMinutes(),
                in.location() != null ? blank(in.location()) : m.location(), chair,
                in.memberIds() != null ? members(in.memberIds(), chair) : m.memberIds(), m.state(),
                in.minutes() != null ? limit(in.minutes()) : m.minutes(), m.startedAt(), m.completedAt(),
                m.protocolSentAt(), m.createdAt(), m.version());
        cab.update(next);
        return view(meeting(id), me);
    }

    /** «Собрать повестку»: все изменения, которые ждут CAB и ещё не стоят в другой повестке. */
    @Transactional
    MeetingView collect(UUID id, String ifMatch, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        requireOpen(m);
        for (UUID changeId : cab.awaitingCab()) {
            cab.addItem(m.id(), changeId);
        }
        touch(m);
        return view(meeting(id), me);
    }

    @Transactional
    MeetingView addItem(UUID id, String ifMatch, UUID changeId, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        requireOpen(m);
        changes.find(changeId).orElseThrow(() -> ApiException.notFound("Изменение"));
        if (pendingCabSlot(changeId) == null) {
            throw ChangeProblem.clash("cab_not_required", "Изменение не ждёт решения CAB");
        }
        if (!cab.addItem(m.id(), changeId)) {
            throw ChangeProblem.clash("already_on_agenda", "Изменение уже в повестке");
        }
        touch(m);
        return view(meeting(id), me);
    }

    @Transactional
    MeetingView removeItem(UUID id, UUID itemId, Me me) {
        CabStore.Meeting m = cab.lock(id).orElseThrow(() -> ApiException.notFound("Заседание CAB"));
        requireManage(m, me);
        requireOpen(m);
        CabStore.Item item = cab.item(itemId).filter(i -> i.meetingId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Пункт повестки"));
        if (!cab.removeItem(item.id())) {
            throw ChangeProblem.clash("already_decided", "По пункту уже принято решение");
        }
        touch(m);
        return view(meeting(id), me);
    }

    /** «Начать заседание». */
    @Transactional
    MeetingView start(UUID id, String ifMatch, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        if (!"planned".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Заседание уже начато или завершено");
        }
        cab.update(withState(m, "in_progress", Sql.now(), m.completedAt(), m.protocolSentAt(), m.minutes()));
        return view(meeting(id), me);
    }

    @Transactional
    MeetingView vote(UUID id, UUID itemId, String vote, String comment, Me me) {
        CabStore.Meeting m = meeting(id);
        if (!m.participant(me.userId())) {
            throw ApiException.forbidden();
        }
        requireOpen(m);
        CabStore.Item item = cab.item(itemId).filter(i -> i.meetingId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Пункт повестки"));
        ChangeRow c = changes.find(item.changeId()).orElseThrow(() -> ApiException.notFound("Изменение"));
        decisions.requireNotAuthor(c.authorId);
        if (!"pending".equals(item.decision())) {
            throw ChangeProblem.clash("already_decided", "По пункту уже принято решение");
        }
        if (!Set.of("approve", "reject", "abstain").contains(vote)) {
            throw ChangeProblem.field("vote", "Голос: approve, reject или abstain");
        }
        cab.vote(item.id(), me.userId(), vote, blank(comment));
        return view(meeting(id), me);
    }

    /** «Утвердить», «Отклонить», «Отложить» — председатель или менеджер изменений, не автор изменения (ACC-05). */
    @Transactional
    MeetingView decide(UUID id, UUID itemId, String decision, String comment, Me me) {
        CabStore.Meeting m = cab.lock(id).orElseThrow(() -> ApiException.notFound("Заседание CAB"));
        requireManage(m, me);
        if (!"in_progress".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Решения принимаются на начатом заседании");
        }
        CabStore.Item item = cab.item(itemId).filter(i -> i.meetingId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Пункт повестки"));
        if (!"pending".equals(item.decision())) {
            throw ChangeProblem.clash("already_decided", "По пункту уже принято решение");
        }
        ChangeRow c = changes.lock(item.changeId()).orElseThrow(() -> ApiException.notFound("Изменение"));
        decisions.requireNotAuthor(c.authorId);
        String note = blank(comment);
        Instant now = Sql.now();
        switch (decision) {
            case "approve", "reject" -> {
                boolean approve = "approve".equals(decision);
                if (!approve && note == null) {
                    throw ChangeProblem.field("comment", "Укажите причину отклонения");
                }
                ChangeStore.Approval slot = pendingCabSlot(c.id);
                if (slot == null) {
                    throw ChangeProblem.clash("cab_not_required", "Изменение не ждёт решения CAB");
                }
                if (!slot.postFactum() && c.state != ChangeState.APPROVAL) {
                    throw ChangeProblem.clash("invalid_state", "Изменение не на согласовании");
                }
                String text = "Решение CAB «" + m.title() + "»" + (note == null ? "" : ": " + note);
                changes.decide(slot.id(), approve ? "approved" : "rejected", text, me.userId(), now);
                cab.decide(item.id(), approve ? "approved" : "rejected", note, me.userId(), now);
                changeService.afterDecision(c, slot, approve, text, me);
            }
            case "defer" -> cab.decide(item.id(), "deferred", note, me.userId(), now);
            default -> throw ChangeProblem.field("decision", "Решение: approve, reject или defer");
        }
        return view(meeting(id), me);
    }

    /** Завершает заседание: пункты без решения откладываются. */
    @Transactional
    MeetingView complete(UUID id, String ifMatch, String minutes, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        if (!"in_progress".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Заседание не начато");
        }
        Instant now = Sql.now();
        for (CabStore.Item i : cab.items(id)) {
            if ("pending".equals(i.decision())) {
                cab.decide(i.id(), "deferred", "Не рассмотрено на заседании", me.userId(), now);
            }
        }
        cab.update(withState(m, "completed", m.startedAt(), now, m.protocolSentAt(),
                minutes != null ? limit(minutes) : m.minutes()));
        return view(meeting(id), me);
    }

    @Transactional
    MeetingView cancel(UUID id, String ifMatch, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        if (!"planned".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Отменить можно только запланированное заседание");
        }
        cab.update(withState(m, "cancelled", null, null, null, m.minutes()));
        return view(meeting(id), me);
    }

    /** «Разослать протокол»: участникам заседания и авторам изменений из повестки. */
    @Transactional
    MeetingView sendProtocol(UUID id, String ifMatch, Me me) {
        CabStore.Meeting m = forManage(id, ifMatch, me);
        if (!"completed".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Протокол рассылается после завершения заседания");
        }
        String protocol = protocol(m);
        Set<UUID> people = new LinkedHashSet<>();
        people.add(m.chairId());
        people.addAll(m.memberIds());
        for (CabStore.Item i : cab.items(id)) {
            changes.find(i.changeId()).ifPresent(c -> people.add(c.authorId));
        }
        for (UUID u : people) {
            if (directory.findUser(u).map(x -> x.active()).orElse(false)) {
                notifier.notify(u, "cab", "Протокол CAB: " + m.title(), protocol, "/agent/changes/cab/" + m.id(), true);
            }
        }
        cab.update(withState(m, m.state(), m.startedAt(), m.completedAt(), Sql.now(), m.minutes()));
        return view(meeting(id), me);
    }

    /** Текст протокола: решения по пунктам повестки и записи секретаря. Без конфиденциальных изменений. */
    String protocol(CabStore.Meeting m) {
        StringBuilder sb = new StringBuilder();
        sb.append("Заседание CAB «").append(m.title()).append("», ").append(m.startsAt()).append("\n\n");
        for (CabStore.Item i : cab.items(m.id()).stream().sorted(Comparator.comparingInt(CabStore.Item::seq)).toList()) {
            ChangeRow c = changes.find(i.changeId()).orElse(null);
            if (c == null) {
                continue;
            }
            sb.append(i.seq()).append(". ").append(c.number);
            if (!c.confidential) {
                sb.append(" ").append(c.title);
            }
            sb.append(" — ").append(decisionLabel(i.decision()));
            if (i.comment() != null && !c.confidential) {
                sb.append(": ").append(i.comment());
            }
            sb.append('\n');
        }
        if (m.minutes() != null && !m.minutes().isBlank()) {
            sb.append("\nЗаписи заседания:\n").append(m.minutes());
        }
        return sb.toString();
    }

    private static String decisionLabel(String d) {
        return switch (d) {
            case "approved" -> "утверждено";
            case "rejected" -> "отклонено";
            case "deferred" -> "отложено";
            default -> "без решения";
        };
    }

    // ---------------------------------------------------------------- общее

    /** Открытый слот CAB изменения: до работ, затем последующий. */
    ChangeStore.Approval pendingCabSlot(UUID changeId) {
        return changes.approvals(changeId).stream()
                .filter(a -> "cab".equals(a.kind()) && a.pending())
                .min(Comparator.comparing(ChangeStore.Approval::postFactum))
                .orElse(null);
    }

    private CabStore.Meeting forManage(UUID id, String ifMatch, Me me) {
        CabStore.Meeting m = cab.lock(id).orElseThrow(() -> ApiException.notFound("Заседание CAB"));
        requireManage(m, me);
        ru.easyitsm.platform.web.ETags.requireMatch(ifMatch, m.version());
        return m;
    }

    private static void requireManage(CabStore.Meeting m, Me me) {
        if (!me.manager() && !me.is(m.chairId())) {
            throw ApiException.forbidden();
        }
    }

    private static void requireOpen(CabStore.Meeting m) {
        if (!"planned".equals(m.state()) && !"in_progress".equals(m.state())) {
            throw ChangeProblem.clash("invalid_state", "Заседание завершено или отменено");
        }
    }

    private void touch(CabStore.Meeting m) {
        cab.update(m);
    }

    private static CabStore.Meeting withState(CabStore.Meeting m, String state, Instant startedAt, Instant completedAt,
                                              Instant sentAt, String minutes) {
        return new CabStore.Meeting(m.id(), m.title(), m.startsAt(), m.durationMinutes(), m.location(), m.chairId(),
                m.memberIds(), state, minutes, startedAt, completedAt, sentAt, m.createdAt(), m.version());
    }

    private List<UUID> members(List<UUID> ids, UUID chair) {
        List<UUID> members = ChangeService.distinct(ids).stream().filter(u -> !u.equals(chair)).toList();
        if (members.size() > 50) {
            throw ChangeProblem.field("memberIds", "Не больше 50 участников");
        }
        Set<UUID> known = directory.findUsers(members).stream().map(u -> u.id()).collect(Collectors.toSet());
        if (!known.containsAll(members) || directory.findUser(chair).isEmpty()) {
            throw ChangeProblem.field("memberIds", "Участник не найден");
        }
        return members;
    }

    private static int duration(Integer v) {
        int d = v == null ? 60 : v;
        if (d < 5 || d > 1440) {
            throw ChangeProblem.field("durationMinutes", "Длительность — от 5 минут до суток");
        }
        return d;
    }

    private static String limit(String minutes) {
        if (minutes.length() > 50_000) {
            throw ChangeProblem.field("minutes", "Слишком длинные записи");
        }
        return minutes;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    static <T> Map<UUID, T> index(List<T> items, Function<T, UUID> key) {
        return items.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }
}
