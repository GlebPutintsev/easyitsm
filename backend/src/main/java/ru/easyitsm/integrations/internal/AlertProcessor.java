package ru.easyitsm.integrations.internal;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.SecretScanner;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Один алерт — одна транзакция в арендаторе источника: дедупликация по отпечатку и действие с инцидентом.
 * <ul>
 *   <li>новый отпечаток — инцидент от имени служебного пользователя источника (канал monitoring), влияние
 *       и срочность — по важности;</li>
 *   <li>повтор активного алерта — только счётчик; выросла важность — влияние и срочность поднимаются, в ленте
 *       заметка; инцидент уже решён — публичный ответ «заявителя» возвращает его в работу (INC-08);</li>
 *   <li>инцидент закрыт вручную, а алерт ещё приходит — новый инцидент;</li>
 *   <li>«устранено» — алерт закрывается, инцидент решается переходом цикла, если он возможен, иначе заметка.</li>
 * </ul>
 * Одновременные события одного отпечатка упорядочивает рекомендательная блокировка.
 */
@Component
class AlertProcessor {

    private static final Logger log = LoggerFactory.getLogger(AlertProcessor.class);
    static final String TRIGGER = "monitoring";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'");

    /** Итог одного алерта для ответа источнику и журнала. */
    record Outcome(String fingerprint, String outcome, UUID ticketId, String ticket, String detail) {
    }

    private final AlertStore alerts;
    private final EventLog events;
    private final TicketCommands tickets;
    private final TicketQueries ticketQueries;
    private final SecretScanner secrets;
    private final TransactionTemplate tx;

    AlertProcessor(AlertStore alerts, EventLog events, TicketCommands tickets, TicketQueries ticketQueries,
                   SecretScanner secrets, PlatformTransactionManager txManager) {
        this.alerts = alerts;
        this.events = events;
        this.tickets = tickets;
        this.ticketQueries = ticketQueries;
        this.secrets = secrets;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Вызывается в арендаторе источника. Ошибка одного алерта не мешает остальным: она пишется в журнал. */
    Outcome process(MonitoringSource source, Alert alert) {
        try {
            return tx.execute(s -> {
                Outcome o = decide(source, alert);
                events.write(source.id(), alert, o.outcome(), o.ticketId(), o.ticket(), o.detail());
                return o;
            });
        } catch (RuntimeException e) {
            log.warn("Алерт {} источника {} не обработан", alert.fingerprint(), source.id(), e);
            String detail = e instanceof ApiException api ? api.code() + ": " + api.getMessage() : "внутренняя ошибка";
            tx.executeWithoutResult(s -> events.write(source.id(), alert, "error", null, null, detail));
            return new Outcome(alert.fingerprint(), "error", null, null, detail);
        }
    }

    /** Событие без разбора (ошибка формата) — только в журнал. */
    void rejected(MonitoringSource source, String detail) {
        tx.executeWithoutResult(s -> events.write(source.id(), null, "error", null, null, detail));
    }

    private Outcome decide(MonitoringSource source, Alert a) {
        alerts.lock(source.id(), a.fingerprint());
        Optional<AlertStore.Row> active = alerts.firing(source.id(), a.fingerprint());
        if (!a.firing()) {
            return resolved(source, a, active);
        }
        if (active.isPresent()) {
            AlertStore.Row row = active.get();
            Optional<TicketSnapshot> ticket = ticketQueries.find(row.ticketId());
            if (ticket.isPresent() && !ticket.get().statusCategory().isFinal()) {
                return repeated(source, a, row, ticket.get());
            }
            // Инцидент закрыли, а проблема осталась: прежний алерт завершается, ниже — новый инцидент.
            alerts.resolve(row.id());
        }
        Severity min = Severity.isCode(source.minSeverity()) ? Severity.fromCode(source.minSeverity()) : Severity.INFO;
        if (!a.severity().atLeast(min)) {
            return new Outcome(a.fingerprint(), "ignored", null, null, "важность ниже порога источника");
        }
        TicketRef ref = tickets.create(newIncident(source, a));
        alerts.insert(source.id(), a, ref.id(), ref.number());
        return new Outcome(a.fingerprint(), "created", ref.id(), ref.number(), null);
    }

    private Outcome repeated(MonitoringSource source, Alert a, AlertStore.Row row, TicketSnapshot t) {
        Severity worst = a.severity().atLeast(row.severity()) ? a.severity() : row.severity();
        alerts.repeat(row.id(), worst);
        if (t.statusCategory() == StatusCategory.RESOLVED) {
            // Ответ «заявителя» в решённой заявке возвращает её в работу по циклу (INC-08).
            tickets.addComment(t.id(), source.requesterId(), "Мониторинг: проблема повторилась.\n\n" + summary(a),
                    Visibility.PUBLIC);
            return new Outcome(a.fingerprint(), "reopened", t.id(), t.number(), null);
        }
        if (worst != row.severity()) {
            MonitoringSource.Impact impact = source.impactOf(worst);
            String reason = "Мониторинг: важность выросла до «" + label(worst) + "»";
            tickets.change(t.id(), new TicketChange(null, level(impact.impact()), level(impact.urgency()), null, null,
                    false, reason));
            tickets.addComment(t.id(), null, reason + ".\n\n" + summary(a), Visibility.INTERNAL);
            return new Outcome(a.fingerprint(), "escalated", t.id(), t.number(), label(row.severity()) + " → " + label(worst));
        }
        return new Outcome(a.fingerprint(), "updated", t.id(), t.number(), "повтор №" + (row.occurrences() + 1));
    }

    private Outcome resolved(MonitoringSource source, Alert a, Optional<AlertStore.Row> active) {
        if (active.isEmpty()) {
            return new Outcome(a.fingerprint(), "ignored", null, null, "нет активного алерта с таким отпечатком");
        }
        AlertStore.Row row = active.get();
        alerts.resolve(row.id());
        Optional<TicketSnapshot> ticket = ticketQueries.find(row.ticketId());
        if (ticket.isEmpty() || ticket.get().statusCategory().isFinal()
                || ticket.get().statusCategory() == StatusCategory.RESOLVED) {
            return new Outcome(a.fingerprint(), "resolved", row.ticketId(), row.ticketNumber(), "инцидент уже решён или закрыт");
        }
        String note = "Мониторинг: проблема устранена.";
        if (source.autoResolve()) {
            Optional<TicketRef> done = tickets.resolveIfPossible(row.ticketId(), note,
                    source.resolutionCode(), "Проблема устранена по данным мониторинга «" + source.name() + "».",
                    TRIGGER);
            if (done.isPresent()) {
                return new Outcome(a.fingerprint(), "resolved", row.ticketId(), row.ticketNumber(), "инцидент решён");
            }
            tickets.addComment(row.ticketId(), null, note + " Решите заявку, когда убедитесь в этом.",
                    Visibility.INTERNAL);
            return new Outcome(a.fingerprint(), "resolved", row.ticketId(), row.ticketNumber(),
                    "переход в «Решена» сейчас недоступен, добавлена заметка");
        }
        tickets.addComment(row.ticketId(), null, note, Visibility.INTERNAL);
        return new Outcome(a.fingerprint(), "resolved", row.ticketId(), row.ticketNumber(), "добавлена заметка");
    }

    private NewTicket newIncident(MonitoringSource source, Alert a) {
        String title = secrets.mask(a.title()).replaceAll("\\s+", " ").strip();
        title = AlertFormats.cut(title.isEmpty() ? "Алерт мониторинга" : title, AlertFormats.MAX_TITLE);
        MonitoringSource.Impact impact = source.impactOf(a.severity());
        return new NewTicket(TicketType.INCIDENT, title, description(source, a), source.requesterId(), null, null,
                Channel.MONITORING, level(impact.impact()), level(impact.urgency()), null, null, null, null,
                source.groupId(), null, Map.of(), false, null);
    }

    private String description(MonitoringSource source, Alert a) {
        StringBuilder sb = new StringBuilder();
        if (a.description() != null && !a.description().isBlank()) {
            sb.append(a.description().strip()).append("\n\n");
        }
        sb.append(summary(a));
        sb.append("\nИсточник: ").append(source.name()).append(" (").append(kindLabel(source.kind())).append(")");
        sb.append("\nОтпечаток: ").append(a.fingerprint());
        return AlertFormats.cut(sb.toString(), AlertFormats.MAX_DESCRIPTION);
    }

    /** Важность, начало, ссылка и метки — для описания и заметок. */
    private static String summary(Alert a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Важность: ").append(label(a.severity()));
        if (a.startsAt() != null) {
            sb.append("\nНачало: ").append(TIME.format(a.startsAt().atOffset(ZoneOffset.UTC)));
        }
        if (a.link() != null) {
            sb.append("\nСсылка: ").append(a.link());
        }
        if (!a.details().isEmpty()) {
            sb.append("\nМетки:");
            a.details().forEach((k, v) -> sb.append("\n• ").append(k).append(v.isEmpty() ? "" : ": " + v));
        }
        return sb.toString();
    }

    static String label(Severity s) {
        return switch (s) {
            case CRITICAL -> "Критическая";
            case HIGH -> "Высокая";
            case WARNING -> "Предупреждение";
            case INFO -> "Информация";
        };
    }

    static String kindLabel(String kind) {
        return switch (kind) {
            case "zabbix" -> "Zabbix";
            case "alertmanager" -> "Alertmanager";
            default -> "JSON";
        };
    }

    private static Level level(String code) {
        return code == null ? null : Level.fromCode(code);
    }
}
