package ru.easyitsm.problems.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Новая проблема от другого модуля.
 *
 * @param source       {@code manual}, {@code incident}, {@code recurring} или {@code major_incident}
 * @param incidentIds  затронутые инциденты: связываются с проблемой (PRB-01)
 * @param rootCause    первая гипотеза причины, может быть пустой
 * @param timeline     события для хронологии анализа (PRB-02), например из командного центра
 * @param requesterId  автор проблемы
 */
public record NewProblem(
        String title,
        String description,
        String source,
        List<UUID> incidentIds,
        UUID serviceId,
        UUID ciId,
        UUID groupId,
        String rootCause,
        List<TimelineItem> timeline,
        UUID requesterId) {

    public NewProblem {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(requesterId, "requesterId");
        description = description == null ? "" : description;
        source = source == null ? "manual" : source;
        incidentIds = incidentIds == null ? List.of() : List.copyOf(incidentIds);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        rootCause = rootCause == null ? "" : rootCause;
    }

    /** Событие хронологии: время и текст. */
    public record TimelineItem(java.time.Instant at, String text) {
    }
}
