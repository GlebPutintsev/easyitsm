package ru.easyitsm.sla.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Цель политики (SLA-01): показатель, приоритет и срок. Хранится в таблице {@code sla_target} внутри агрегата
 * {@link SlaPolicy}.
 */
class SlaTarget {

    static final String RESPONSE = "response";
    static final String RESOLUTION = "resolution";

    @SuppressWarnings("unused")
    private UUID tenantId;
    private String metric;
    private String priority;
    private int amount;
    /** minutes, hours или business_days. */
    private String unit;
    /** policy — график политики, 24x7 — круглосуточно. */
    private String calendar;
    private List<Integer> thresholds;
    private List<String> warnActions;
    private List<String> breachActions;

    protected SlaTarget() {
    }

    SlaTarget(String metric, String priority, int amount, String unit, String calendar, List<Integer> thresholds,
              List<String> warnActions, List<String> breachActions) {
        this.tenantId = TenantContext.require();
        this.metric = metric;
        this.priority = priority;
        this.amount = amount;
        this.unit = unit;
        this.calendar = calendar;
        this.thresholds = new ArrayList<>(thresholds.stream().distinct().sorted().toList());
        this.warnActions = new ArrayList<>(warnActions);
        this.breachActions = new ArrayList<>(breachActions);
    }

    String metric() {
        return metric;
    }

    String priority() {
        return priority;
    }

    int amount() {
        return amount;
    }

    String unit() {
        return unit;
    }

    boolean aroundTheClock() {
        return "24x7".equals(calendar);
    }

    String calendar() {
        return calendar;
    }

    List<Integer> thresholds() {
        return thresholds;
    }

    List<String> warnActions() {
        return warnActions;
    }

    List<String> breachActions() {
        return breachActions;
    }

    /** Цель в миллисекундах рабочего времени. Рабочие дни считаются по длине обычного дня графика. */
    long targetMs(BusinessCalendar cal) {
        return switch (unit) {
            case "minutes" -> amount * 60_000L;
            case "hours" -> amount * 3_600_000L;
            case "business_days" -> amount * cal.nominalDayMs();
            default -> throw new IllegalStateException("Неизвестная единица срока: " + unit);
        };
    }

    SlaViews.Target toView() {
        return new SlaViews.Target(metric, priority, amount, unit, calendar, thresholds, warnActions, breachActions);
    }
}
