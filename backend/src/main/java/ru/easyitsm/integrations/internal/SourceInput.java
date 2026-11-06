package ru.easyitsm.integrations.internal;

import java.util.Map;
import java.util.UUID;

/**
 * Создание или изменение источника. При изменении null — оставить как было; {@code clearGroup} снимает группу.
 * Без {@code requesterId} при создании модуль заводит служебного пользователя «Мониторинг: имя».
 */
record SourceInput(
        String name,
        String kind,
        Boolean enabled,
        UUID requesterId,
        UUID groupId,
        Boolean clearGroup,
        String minSeverity,
        Map<String, MonitoringSource.Impact> severityMap,
        Boolean autoResolve,
        String resolutionCode) {

    SourceInput over(MonitoringSource s) {
        return new SourceInput(
                name != null ? name : s.name(),
                kind != null ? kind : s.kind(),
                enabled != null ? enabled : s.enabled(),
                requesterId != null ? requesterId : s.requesterId(),
                Boolean.TRUE.equals(clearGroup) ? null : groupId != null ? groupId : s.groupId(),
                false,
                minSeverity != null ? minSeverity : s.minSeverity(),
                severityMap != null ? severityMap : s.severityMap(),
                autoResolve != null ? autoResolve : s.autoResolve(),
                resolutionCode != null ? resolutionCode : s.resolutionCode());
    }
}
