package ru.easyitsm.catalog.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import ru.easyitsm.catalog.internal.form.Condition;

/**
 * Шаг маршрута согласования (REQ-03). Шаги идут последовательно. Внутри шага решение принимают
 * все согласующие ({@code all}), любой из них ({@code any}) или кворум из {@code quorum} человек.
 * Шаг с условием включается в маршрут, только если ответы формы ему удовлетворяют.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record StepDef(
        String name,
        String mode,
        Integer quorum,
        List<UUID> approvers,
        Condition condition,
        Integer dueMinutes,
        Boolean allowDelegation) {

    static final List<String> MODES = List.of("all", "any", "quorum");

    boolean delegationAllowed() {
        return Boolean.TRUE.equals(allowDelegation);
    }

    StepDef withApprovers(List<UUID> list) {
        return new StepDef(name, mode, quorum, list, condition, dueMinutes, allowDelegation);
    }

    /** Сколько одобрений нужно для шага при данном числе согласующих. */
    int required(int approverCount) {
        return switch (mode) {
            case "any" -> 1;
            case "quorum" -> Math.min(quorum == null ? 1 : quorum, approverCount);
            default -> approverCount;
        };
    }
}
