package ru.easyitsm.automation.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Запуск правил по событию (AUT-01) с журналом и защитой от петель (AUT-04).
 * <p>
 * Для каждого включённого правила события проверяются условия по снимку заявки из события. Совпали — запуск:
 * <ol>
 *   <li>то же событие уже обработано этим правилом (повторная доставка из outbox) — ничего не делать;</li>
 *   <li>глубина цепочки больше {@code maxDepth} — пропуск {@code depth_limit};</li>
 *   <li>правило уже есть в цепочке, которая привела к событию, — пропуск {@code loop};</li>
 *   <li>правило выполнило действия на этой заявке {@code rateLimit} раз за {@code rateWindow} — пропуск
 *       {@code rate_limit} (страховка, если цепочка потеряна при повторной доставке);</li>
 *   <li>иначе действия выполняются в одной новой транзакции вместе с записью журнала: всё или ничего.
 *       Ошибка действия — откат и запись {@code failed} с причиной.</li>
 * </ol>
 * Ошибка одного правила не мешает остальным.
 */
@Component
class AutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);

    /** Псевдоправило для действий из настройки SLA ({@code set_priority:P1}): участвует в цепочке как правило. */
    static final UUID SLA_ACTIONS = UUID.nameUUIDFromBytes("easyitsm:automation:sla-actions".getBytes(StandardCharsets.UTF_8));

    /**
     * Событие для правил.
     *
     * @param eventKey   однозначный ключ события: повтор того же события не запускает правило второй раз
     * @param slaActions действия из настройки цели SLA, которые выполняет автоматизация
     */
    record Occurrence(Trigger trigger, TicketSnapshot ticket, Facts facts, String eventKey, List<String> slaActions) {
    }

    private final RuleStore rules;
    private final RunLog runs;
    private final ActionRunner actions;
    private final AutomationProperties props;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    AutomationEngine(RuleStore rules, RunLog runs, ActionRunner actions, AutomationProperties props,
                     PlatformTransactionManager tm) {
        this.rules = rules;
        this.runs = runs;
        this.actions = actions;
        this.props = props;
        // Слушатели вызываются после фиксации чужой транзакции: работа идёт только в новой транзакции.
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    void handle(Occurrence o) {
        for (Rule rule : read(() -> rules.enabledFor(o.trigger()))) {
            if (Boolean.TRUE.equals(ConditionEvaluator.evaluate(rule.conditions(), o.facts()).matched())) {
                run(rule.id(), rule.id(), rule.name(), rule.version(), rule.actions(), o);
            }
        }
        for (String a : o.slaActions() == null ? List.<String>of() : o.slaActions()) {
            if (a != null && a.matches("set_priority:P[1-4]")) {
                run(null, SLA_ACTIONS, "SLA: " + a, 0, List.of(Action.setPriority(a.substring(a.indexOf(':') + 1))), o);
            }
        }
    }

    private void run(UUID ruleId, UUID chainId, String name, long version, List<Action> ruleActions, Occurrence o) {
        String runKey = chainId + "|" + o.eventKey();
        int depth = RunChain.depth() + 1;
        List<UUID> chain = RunChain.rules();
        Instant started = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long t0 = System.nanoTime();
        UUID ticketId = o.ticket().id();
        try {
            if (Boolean.TRUE.equals(read(() -> runs.exists(runKey)))) {
                return;
            }
            String skip = depth > props.maxDepth() ? "depth_limit"
                    : chain.contains(chainId) ? "loop"
                    : read(() -> runs.recentRuns(ruleId, ticketId, props.rateWindow())) >= props.rateLimit()
                            ? "rate_limit" : null;
            if (skip != null) {
                log.warn("Правило автоматизации «{}» остановлено защитой от петель: {}, заявка {}, глубина {}",
                        name, skip, o.ticket().number(), depth);
                write(() -> runs.insert(new Run(RunLog.newId(), ruleId, name, version, o.trigger().code(), ticketId,
                        o.ticket().number(), depth, chain, "skipped", skip, List.of(), null, null, started,
                        millis(t0)), runKey));
                return;
            }
            RunChain.within(chainId, () -> tx.execute(status -> {
                runs.limitLockWait();
                List<Map<String, Object>> results = actions.apply(name, ruleActions, ticketId);
                runs.insert(new Run(RunLog.newId(), ruleId, name, version, o.trigger().code(), ticketId,
                        o.ticket().number(), depth, chain, "success", null, results, null, null, started, millis(t0)),
                        runKey);
                return null;
            }));
        } catch (ActionFailed e) {
            if ("internal_error".equals(e.code())) {
                log.error("Сбой действия правила автоматизации «{}» на заявке {}", name, o.ticket().number(), e.getCause());
            } else {
                log.info("Правило автоматизации «{}» не выполнено на заявке {}: {}", name, o.ticket().number(),
                        e.getMessage());
            }
            String error = "Действие " + (e.index() + 1) + " (" + e.type() + "): " + e.getMessage();
            failed(ruleId, name, version, o, runKey, depth, chain, started, t0, e.code(), error);
        } catch (RuntimeException e) {
            log.error("Сбой правила автоматизации «{}» на заявке {}", name, o.ticket().number(), e);
            failed(ruleId, name, version, o, runKey, depth, chain, started, t0, "internal_error",
                    "Внутренняя ошибка при запуске правила");
        }
    }

    private void failed(UUID ruleId, String name, long version, Occurrence o, String runKey, int depth, List<UUID> chain,
                        Instant started, long t0, String code, String error) {
        try {
            write(() -> runs.insert(new Run(RunLog.newId(), ruleId, name, version, o.trigger().code(), o.ticket().id(),
                    o.ticket().number(), depth, chain, "failed", null, List.of(), code, error, started, millis(t0)),
                    runKey));
        } catch (RuntimeException e) {
            log.error("Не удалось записать ошибку правила «{}» в журнал", name, e);
        }
    }

    private <T> T read(Supplier<T> query) {
        return readTx.execute(status -> query.get());
    }

    private void write(Runnable action) {
        tx.executeWithoutResult(status -> action.run());
    }

    private static int millis(long t0) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
    }
}
