package ru.easyitsm.notifications.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Собирает получателей события и рассылает уведомления в одной транзакции в арендаторе события.
 * <p>
 * Слушатели работают после фиксации транзакции модуля-источника ({@code @TransactionalEventListener}) через
 * outbox: при ошибке публикация остаётся незавершённой и повторяется после перезапуска. Транзакция рассылки
 * новая ({@code REQUIRES_NEW}) и открывается после установки арендатора, поэтому RLS видит его строки.
 * Если рассылка упала, откатывается целиком — повтор не создаст дублей.
 */
@Service
class NotificationPlanner {

    private static final Logger log = LoggerFactory.getLogger(NotificationPlanner.class);

    private final UserDirectory users;
    private final Dispatcher dispatcher;
    private final TransactionTemplate tx;

    NotificationPlanner(UserDirectory users, Dispatcher dispatcher, PlatformTransactionManager txManager) {
        this.users = users;
        this.dispatcher = dispatcher;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void inTenant(UUID tenantId, Consumer<Plan> build) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(s -> {
            Plan plan = new Plan();
            build.accept(plan);
            plan.execute();
        }));
    }

    /** Получатели одного события. Первое правило для пользователя побеждает. */
    final class Plan {

        private record Entry(UUID userId, Dispatcher.Message message, Dispatcher.Channels channels,
                             Map<String, String> sensitive, TicketSnapshot ticket, String path) {
        }

        private final Map<UUID, Entry> entries = new LinkedHashMap<>();
        private final List<Runnable> direct = new ArrayList<>();
        private final Set<UUID> excluded = new HashSet<>();
        private final Set<String> selfEmail = new HashSet<>();
        private UUID actorId;

        void exclude(UUID actor) {
            if (actor != null) {
                excluded.add(actor);
                actorId = actor;
            }
        }

        /** Автор действия всё же получает письмо этого вида (подтверждение регистрации заявки). */
        void allowSelfEmail(String kind) {
            selfEmail.add(kind);
        }

        void requester(TicketSnapshot t, String kind, Map<String, String> extra, boolean email, boolean push) {
            for (UUID id : TicketNotifications.requesterSide(t)) {
                add(id, t, kind, extra, "/portal/tickets/" + t.number(), new Dispatcher.Channels(true, email, push));
            }
        }

        /** Один участник со стороны заявителя (заявитель или получатель). */
        void requesterOne(UUID id, TicketSnapshot t, String kind, Map<String, String> extra) {
            add(id, t, kind, extra, "/portal/tickets/" + t.number(), new Dispatcher.Channels(true, true, true));
        }

        void support(Collection<UUID> ids, TicketSnapshot t, String kind, Map<String, String> extra, boolean email,
                     boolean push) {
            for (UUID id : TicketNotifications.ids(ids)) {
                add(id, t, kind, extra, "/agent/tickets/" + t.number(), new Dispatcher.Channels(true, email, push));
            }
        }

        /** Новая заявка в очереди: участникам группы в центр уведомлений и push, без писем. */
        void group(UUID groupId, TicketSnapshot t, String kind) {
            String name = users.findGroup(groupId).map(GroupSummary::name).orElse("");
            for (UUID id : users.activeMembersOf(groupId)) {
                add(id, t, kind, Map.of("group.name", name), "/agent/tickets/" + t.number(),
                        new Dispatcher.Channels(true, false, true));
            }
        }

        void groupMembers(UUID groupId, TicketSnapshot t, String kind, Map<String, String> extra) {
            for (UUID id : users.activeMembersOf(groupId)) {
                add(id, t, kind, extra, "/agent/tickets/" + t.number(), new Dispatcher.Channels(true, false, true));
            }
        }

        /** Уведомление без заявки или с готовой моделью (согласования, SLA, сообщения других модулей). */
        void user(UUID userId, String kind, Map<String, String> model, TicketSnapshot ticket, String path,
                  Dispatcher.Channels channels) {
            if (userId == null || entries.containsKey(userId)) {
                return;
            }
            entries.put(userId, new Entry(userId, new Dispatcher.Message(kind, model,
                    ticket == null ? null : ticket.id(), ticket == null ? null : ticket.number(), path), channels,
                    Map.of(), ticket, path));
        }

        /** Письмо на адрес без центра уведомлений (сброс пароля: адрес из события). */
        void address(Dispatcher.Recipient recipient, String kind, Map<String, String> model) {
            direct.add(() -> dispatcher.send(recipient, new Dispatcher.Message(kind, model, null, null, null),
                    new Dispatcher.Channels(false, true, false)));
        }

        private void add(UUID userId, TicketSnapshot t, String kind, Map<String, String> extra, String path,
                         Dispatcher.Channels channels) {
            if (userId == null || entries.containsKey(userId)) {
                return;
            }
            entries.put(userId, new Entry(userId, new Dispatcher.Message(kind, Map.of(), t.id(), t.number(), path),
                    channels, extra, t, path));
        }

        void execute() {
            direct.forEach(Runnable::run);
            if (entries.isEmpty()) {
                return;
            }
            Set<UUID> ids = new HashSet<>(entries.keySet());
            if (actorId != null) {
                ids.add(actorId);
            }
            Map<UUID, UserSummary> people = new HashMap<>();
            for (UserSummary u : users.findUsers(ids)) {
                people.put(u.id(), u);
            }
            UserSummary actor = actorId == null ? null : people.get(actorId);
            for (Entry e : entries.values()) {
                UserSummary u = people.get(e.userId());
                if (u == null || !u.active()) {
                    continue;
                }
                Dispatcher.Channels channels = e.channels();
                if (excluded.contains(e.userId())) {
                    if (!selfEmail.contains(e.message().kind())) {
                        continue;
                    }
                    channels = new Dispatcher.Channels(false, channels.email(), false);
                }
                Dispatcher.Recipient r = Dispatcher.Recipient.of(u);
                Map<String, String> model = new HashMap<>();
                if (e.ticket() != null) {
                    model.putAll(NotificationModel.ticket(e.ticket(), r.locale(), url(e.path())));
                }
                model.putAll(e.message().model());
                e.sensitive().forEach((k, v) -> NotificationModel.putSensitive(model, k, v));
                model.put("actor.name", actor != null ? actor.displayName()
                        : "en".equals(r.locale()) ? "Service Desk" : "Служба поддержки");
                try {
                    dispatcher.send(r, new Dispatcher.Message(e.message().kind(), model, e.message().ticketId(),
                            e.message().ticketNumber(), e.path()), channels);
                } catch (RuntimeException ex) {
                    log.error("Уведомление {} пользователю {} не создано", e.message().kind(), e.userId(), ex);
                    throw ex;
                }
            }
        }
    }

    String url(String path) {
        return dispatcher.url(path);
    }
}
