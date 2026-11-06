package ru.easyitsm.automation.internal;

import java.util.UUID;

/**
 * Действие правила. Набор полей зависит от {@code type}:
 * <ul>
 *   <li>{@code assign} — {@code groupId} и/или {@code assigneeId}: назначить группу (исполнителя выберет
 *       распределение группы) или исполнителя;</li>
 *   <li>{@code set_priority} — {@code priority} P1–P4, ручной приоритет поверх матрицы;</li>
 *   <li>{@code set_status} — {@code status} (код статуса цикла), {@code body} — комментарий перехода,
 *       {@code reasonCode}, {@code resolutionCode};</li>
 *   <li>{@code add_comment} — {@code body} с подстановками {@code {{number}}}, {@code {{title}}},
 *       {@code {{priority}}}, {@code {{status}}}, {@code {{rule}}}; {@code visibility} public или internal;</li>
 *   <li>{@code add_watcher} — {@code userId};</li>
 *   <li>{@code escalate} — {@code kind} functional (нужна {@code groupId}) или hierarchical, {@code reason}.</li>
 * </ul>
 */
record Action(
        String type,
        UUID groupId,
        UUID assigneeId,
        String priority,
        String status,
        String body,
        String visibility,
        UUID userId,
        String reasonCode,
        String resolutionCode,
        String kind,
        String reason) {

    static final java.util.Set<String> TYPES = java.util.Set.of("assign", "set_priority", "set_status", "add_comment",
            "add_watcher", "escalate");

    static Action setPriority(String priority) {
        return new Action("set_priority", null, null, priority, null, null, null, null, null, null, null, null);
    }
}
