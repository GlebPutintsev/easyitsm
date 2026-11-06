package ru.easyitsm.integrations.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Источник событий мониторинга. Токена в записи нет: он показывается только при создании и перевыпуске.
 *
 * @param kind        zabbix, alertmanager (в том числе Grafana) или generic
 * @param requesterId служебный пользователь, от имени которого создаются инциденты
 * @param groupId     группа новых инцидентов; null — по правилам маршрутизации
 * @param minSeverity ниже этой важности события только пишутся в журнал
 * @param severityMap влияние и срочность нового инцидента по важности алерта
 * @param autoResolve «проблема устранена» решает инцидент, если цикл это позволяет
 */
record MonitoringSource(
        UUID id,
        String name,
        String kind,
        String tokenHint,
        boolean enabled,
        UUID requesterId,
        UUID groupId,
        String minSeverity,
        Map<String, Impact> severityMap,
        boolean autoResolve,
        String resolutionCode,
        Instant lastEventAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** Влияние и срочность: high, medium, low. */
    record Impact(String impact, String urgency) {
    }

    static Map<String, Impact> defaultSeverityMap() {
        Map<String, Impact> m = new LinkedHashMap<>();
        m.put("critical", new Impact("high", "high"));
        m.put("high", new Impact("high", "medium"));
        m.put("warning", new Impact("medium", "medium"));
        m.put("info", new Impact("low", "low"));
        return m;
    }

    Impact impactOf(Severity s) {
        Impact i = severityMap == null ? null : severityMap.get(s.code());
        return i != null ? i : defaultSeverityMap().get(s.code());
    }

    /** Снимок для журнала версий настроек: без токена. */
    Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("enabled", enabled);
        m.put("requesterId", requesterId);
        m.put("groupId", groupId);
        m.put("minSeverity", minSeverity);
        m.put("severityMap", severityMap);
        m.put("autoResolve", autoResolve);
        m.put("resolutionCode", resolutionCode);
        m.put("tokenHint", tokenHint);
        return m;
    }
}
