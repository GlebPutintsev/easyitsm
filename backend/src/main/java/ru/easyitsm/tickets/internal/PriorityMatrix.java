package ru.easyitsm.tickets.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;

/** Матрица приоритета влияние × срочность (раздел 5.2). Ключи — коды уровней, значения — P1…P4. */
record PriorityMatrix(Map<String, Map<String, String>> cells, Long version) {

    static PriorityMatrix defaults() {
        Map<String, Map<String, String>> cells = new LinkedHashMap<>();
        cells.put("high", Map.of("high", "P1", "medium", "P2", "low", "P3"));
        cells.put("medium", Map.of("high", "P2", "medium", "P3", "low", "P4"));
        cells.put("low", Map.of("high", "P3", "medium", "P4", "low", "P4"));
        return new PriorityMatrix(cells, null);
    }

    Priority priority(Level impact, Level urgency) {
        return Priority.fromCode(cells.get(impact.code()).get(urgency.code()));
    }

    /** Все девять клеток заданы допустимыми приоритетами. */
    void validate() {
        for (Level i : Level.values()) {
            Map<String, String> row = cells == null ? null : cells.get(i.code());
            for (Level u : Level.values()) {
                String p = row == null ? null : row.get(u.code());
                try {
                    Priority.fromCode(p);
                } catch (RuntimeException e) {
                    throw TicketProblem.fieldRequired("cells." + i.code() + "." + u.code(),
                            "Для каждой пары влияния и срочности нужен приоритет P1–P4");
                }
                if (p == null) {
                    throw TicketProblem.fieldRequired("cells." + i.code() + "." + u.code(),
                            "Для каждой пары влияния и срочности нужен приоритет P1–P4");
                }
            }
        }
    }

    PriorityMatrix withVersion(Long v) {
        return new PriorityMatrix(cells, v);
    }
}
