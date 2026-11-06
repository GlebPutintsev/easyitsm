package ru.easyitsm.automation.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Правила, проверка правила на заявке и журнал запусков для REST. */
@Service
class AutomationService {

    /** Итог проверки правила на заявке (AUT-03 в упрощённом виде): ничего не меняет. */
    record TestResult(Map<String, Object> ticket, Boolean matched, List<Boolean> conditions, List<String> unknownFields,
                      List<Map<String, Object>> actions) {
    }

    private final RuleStore rules;
    private final RunLog runs;
    private final RuleValidator validator;
    private final ActionRunner actions;
    private final TicketQueries tickets;

    AutomationService(RuleStore rules, RunLog runs, RuleValidator validator, ActionRunner actions,
                      TicketQueries tickets) {
        this.rules = rules;
        this.runs = runs;
        this.validator = validator;
        this.actions = actions;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    List<Rule> list() {
        return rules.list();
    }

    @Transactional(readOnly = true)
    Rule get(UUID id) {
        return find(id);
    }

    @Transactional
    Rule create(RuleInput input) {
        validator.validate(input);
        return find(rules.insert(input));
    }

    @Transactional
    Rule update(UUID id, String ifMatch, RuleInput input) {
        Rule current = find(id);
        ETags.requireMatch(ifMatch, current.version());
        RuleInput merged = input.over(current);
        validator.validate(merged);
        if (!rules.update(id, current.version(), merged)) {
            throw ApiException.conflict("version_conflict", "Правило изменено другим пользователем. Обновите и повторите");
        }
        return find(id);
    }

    @Transactional
    void delete(UUID id) {
        if (!rules.delete(id)) {
            throw ApiException.notFound("Правило");
        }
    }

    @Transactional(readOnly = true)
    TestResult test(RuleInput input, String ticketRef) {
        RuleInput rule = input.name() == null || input.name().isBlank()
                ? new RuleInput("Проверка", input.description(), input.trigger(), input.conditions(), input.actions(),
                        input.enabled(), input.position())
                : input;
        validator.validate(rule);
        TicketSnapshot t = resolveTicket(ticketRef);
        Trigger trigger = Trigger.of(rule.trigger()).orElseThrow();
        Conditions conditions = rule.conditions() == null ? Conditions.always() : rule.conditions();
        ConditionEvaluator.Evaluation ev = ConditionEvaluator.evaluate(conditions, Facts.of(t));
        List<String> unknown = conditions.itemsOrEmpty().stream().map(Condition::field)
                .filter(trigger.eventFields()::contains).distinct().toList();
        Map<String, Object> ticket = new java.util.LinkedHashMap<>();
        ticket.put("id", t.id());
        ticket.put("number", t.number());
        ticket.put("title", t.title());
        ticket.put("statusCode", t.statusCode());
        ticket.put("priority", t.priority() == null ? null : t.priority().name());
        return new TestResult(ticket, ev.matched(), ev.items(), unknown, actions.plan(rule.name(), rule.actions(), t));
    }

    @Transactional(readOnly = true)
    CursorPage<Run> runs(UUID ruleId, String status, String ticketRef, PageParams page) {
        if (status != null && !List.of("success", "failed", "skipped").contains(status)) {
            throw ApiException.badRequest("invalid_filter", "Статус: success, failed или skipped");
        }
        UUID ticketId = null;
        if (ticketRef != null && !ticketRef.isBlank()) {
            UUID id = RuleValidator.uuid(ticketRef);
            ticketId = id != null ? id : tickets.findByNumber(ticketRef.strip().toUpperCase(java.util.Locale.ROOT))
                    .map(TicketSnapshot::id).orElse(null);
            if (ticketId == null) {
                return new CursorPage<>(List.of(), null);
            }
        }
        return runs.page(new RunLog.Filter(ruleId, status, ticketId), page);
    }

    @Transactional(readOnly = true)
    Run run(UUID id) {
        return runs.find(id).orElseThrow(() -> ApiException.notFound("Запуск"));
    }

    /**
     * Заявка для проверки: по номеру или id. Конфиденциальная — только если видна администратору по правилам
     * модуля заявок (ACC-04), иначе 404, как в REST заявок.
     */
    private TicketSnapshot resolveTicket(String ref) {
        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest("ticket_required", "Укажите номер заявки для проверки");
        }
        UUID id = RuleValidator.uuid(ref);
        Optional<TicketSnapshot> t = id != null ? tickets.find(id)
                : tickets.findByNumber(ref.strip().toUpperCase(java.util.Locale.ROOT));
        return t.filter(s -> !s.confidential() || tickets.findVisible(s.id()).isPresent())
                .orElseThrow(() -> ApiException.notFound("Заявка"));
    }

    private Rule find(UUID id) {
        return rules.find(id).orElseThrow(() -> ApiException.notFound("Правило"));
    }
}
