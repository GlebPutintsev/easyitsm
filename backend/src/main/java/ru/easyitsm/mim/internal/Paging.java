package ru.easyitsm.mim.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ru.easyitsm.admin.api.DutyRoster;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.mim.events.OnCallPaged;
import ru.easyitsm.mim.internal.MimDtos.Step;
import ru.easyitsm.mim.internal.MimStore.Incident;
import ru.easyitsm.mim.internal.MimStore.Page;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Оповещение дежурных по цепочке (MIM-03). Шаг — группа или пользователь и время ожидания подтверждения. Кого
 * оповещать в группе: дежурных смены ({@link DutyRoster}), без смены — руководителя группы, без него — активных
 * участников (до 20). Нет подтверждения за время шага — следующий шаг; шаги кончились — оповещается менеджер.
 * Канал {@code center} — центр уведомлений и push, {@code email} — ещё и письмо; прочие каналы слушают
 * {@link OnCallPaged}.
 */
@Component
class Paging {

    private static final int MAX_GROUP_TARGETS = 20;

    private final MimStore store;
    private final DutyRoster duty;
    private final UserDirectory directory;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;

    Paging(MimStore store, DutyRoster duty, UserDirectory directory, Notifier notifier,
           ApplicationEventPublisher events) {
        this.store = store;
        this.duty = duty;
        this.directory = directory;
        this.notifier = notifier;
        this.events = events;
    }

    static String link(UUID mimId) {
        return "/agent/major/" + mimId;
    }

    /** Оповещает шаг {@code step}; пустой шаг сразу передаёт следующему. */
    void notifyStep(Page page, Incident incident, String number, int step, UUID actor) {
        Instant now = Db.now();
        List<Step> steps = page.steps();
        int s = step;
        while (s < steps.size()) {
            Set<UUID> users = targets(steps.get(s), now);
            if (!users.isEmpty()) {
                for (UUID u : users) {
                    store.addTarget(page.id(), s, u);
                    String title = "Крупный инцидент " + number + ": подтвердите получение";
                    notifier.notify(u, "major_incident", title, incident.publicTitle(), link(incident.id()),
                            "email".equals(page.channel()));
                    events.publishEvent(new OnCallPaged(TenantContext.require(), now, incident.id(), page.id(), u, s,
                            page.channel(), title, link(incident.id())));
                }
                store.updatePage(page.id(), s, "paging", now.plus(Duration.ofMinutes(steps.get(s).waitMinutes())),
                        null, null);
                store.event(incident.id(), "paged", now, "Оповещение «" + page.name() + "», шаг " + (s + 1) + ": "
                        + names(users), actor, Map.of("pageId", page.id().toString(), "step", s,
                        "userIds", users.stream().map(UUID::toString).toList()));
                return;
            }
            store.event(incident.id(), "paged", now, "Оповещение «" + page.name() + "», шаг " + (s + 1)
                    + ": некого оповестить, следующий шаг", actor, Map.of("pageId", page.id().toString(), "step", s));
            s++;
        }
        exhausted(page, incident, number, now);
    }

    /** Срок шага истёк без подтверждения: следующий шаг или исчерпание. Вызывается задачей worker. */
    void advance(UUID pageId, Instant now, java.util.function.Function<UUID, String> numberOf) {
        Page page = store.findPage(pageId, true).orElse(null);
        if (page == null || !"paging".equals(page.state()) || page.nextStepAt() == null
                || page.nextStepAt().isAfter(now)) {
            return;
        }
        Incident incident = store.find(page.mimId(), false).orElseThrow();
        if (!incident.open()) {
            store.updatePage(page.id(), page.step(), "cancelled", null, null, null);
            return;
        }
        String number = numberOf.apply(incident.ticketId());
        store.event(incident.id(), "page_timeout", now, "Нет подтверждения на шаге " + (page.step() + 1), null,
                Map.of("pageId", page.id().toString(), "step", page.step()));
        notifyStep(page, incident, number, page.step() + 1, null);
    }

    private void exhausted(Page page, Incident incident, String number, Instant now) {
        store.updatePage(page.id(), page.step(), "exhausted", null, null, null);
        store.event(incident.id(), "page_exhausted", now, "Оповещение «" + page.name()
                + "»: никто не подтвердил получение", null, Map.of("pageId", page.id().toString()));
        UUID manager = store.roles(incident.id()).get("manager");
        if (manager != null) {
            notifier.notify(manager, "major_incident", "Крупный инцидент " + number + ": дежурные не ответили",
                    "Оповещение «" + page.name() + "» исчерпано без подтверждения.", link(incident.id()), true);
        }
    }

    /** Кого оповестить на шаге. */
    Set<UUID> targets(Step step, Instant at) {
        Set<UUID> out = new LinkedHashSet<>();
        if (step.userId() != null) {
            directory.findUser(step.userId()).filter(UserSummary::active).ifPresent(u -> out.add(u.id()));
            return out;
        }
        if (step.groupId() == null) {
            return out;
        }
        out.addAll(duty.onDuty(step.groupId(), at));
        if (out.isEmpty()) {
            directory.leaderOf(step.groupId()).ifPresent(out::add);
        }
        if (out.isEmpty()) {
            directory.activeMembersOf(step.groupId()).stream().limit(MAX_GROUP_TARGETS).forEach(out::add);
        }
        return out;
    }

    private String names(Set<UUID> users) {
        List<String> names = new ArrayList<>();
        for (UserSummary u : directory.findUsers(users)) {
            names.add(u.displayName());
        }
        return names.isEmpty() ? users.size() + " чел." : String.join(", ", names);
    }
}
