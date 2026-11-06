package ru.easyitsm.automation.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Цепочка правил в текущем потоке. Слушатели событий вызываются синхронно после фиксации транзакции, в которой
 * правило выполнило действия, поэтому событие от действия правила A обрабатывается внутри {@link #within} для A:
 * глубина и предки известны без передачи через события. Если событие приходит повторно из outbox после
 * перезапуска, цепочка теряется — на этот случай есть ограничение частоты в {@link AutomationEngine}.
 */
final class RunChain {

    private static final ThreadLocal<Deque<UUID>> RULES = ThreadLocal.withInitial(ArrayDeque::new);

    private RunChain() {
    }

    /** Глубина правила, которое выполняет действия сейчас; 0 — событие не от автоматизации. */
    static int depth() {
        return RULES.get().size();
    }

    /** Правила цепочки от первого. */
    static List<UUID> rules() {
        return List.copyOf(RULES.get().reversed());
    }

    static <T> T within(UUID ruleId, Supplier<T> action) {
        Deque<UUID> stack = RULES.get();
        stack.push(ruleId);
        try {
            return action.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                RULES.remove();
            }
        }
    }
}
