package ru.easyitsm.audit.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Какие изменяющие запросы считаются изменением настроек (SEC-29 «настройки»). Правило задаётся по шаблону
 * маршрута ({@code /api/v1/sla/policies/{id}}), поэтому модули не зависят от журнала аудита. Новый экран
 * настроек добавляет сюда свой префикс.
 * <p>
 * Роли, членство в группах, блокировка и вход пишутся по событиям identity, здесь их нет, чтобы не было двойных
 * записей.
 */
final class AuditRoutes {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Префикс маршрута → раздел настроек для фильтра журнала. */
    private static final List<Rule> CONFIG = List.of(
            new Rule("/api/v1/ticket-settings/", "tickets", List.of()),
            new Rule("/api/v1/sla/schedules", "sla", List.of()),
            new Rule("/api/v1/sla/policies", "sla", List.of("/preview")),
            new Rule("/api/v1/sla/setup/", "sla", List.of()),
            new Rule("/api/v1/catalog/categories", "catalog", List.of()),
            new Rule("/api/v1/catalog/items", "catalog", List.of("/favorite", "/order")),
            new Rule("/api/v1/catalog/approval-flows", "catalog", List.of()),
            new Rule("/api/v1/knowledge/categories", "knowledge", List.of()),
            new Rule("/api/v1/groups", "identity", List.of("/members/")),
            new Rule("/api/v1/users", "identity", List.of("/block", "/unblock", "/mfa/reset")),
            new Rule("/api/v1/directory/", "identity", List.of()),
            new Rule("/api/v1/admin/", "platform", List.of()),
            new Rule("/api/v1/automation/rules", "automation", List.of()),
            new Rule("/api/v1/cmdb/classes", "cmdb", List.of()),
            new Rule("/api/v1/cmdb/relation-types", "cmdb", List.of()),
            new Rule("/api/v1/cmdb/sources", "cmdb", List.of("/batches")),
            new Rule("/api/v1/audit/siem", "audit", List.of("/test")),
            new Rule("/api/v1/change-templates", "changes", List.of()),
            new Rule("/api/v1/change-freezes", "changes", List.of()),
            new Rule("/api/v1/change-settings", "changes", List.of()),
            new Rule("/api/v1/integrations/", "integrations", List.of("/integrations/events", "/test", "/retry")),
            new Rule("/api/v1/mim/templates", "mim", List.of()),
            new Rule("/api/v1/mim/chains", "mim", List.of()));

    private AuditRoutes() {
    }

    /** Раздел настроек, если запрос меняет настройки. */
    static Optional<String> configArea(String method, String pattern) {
        if (pattern == null || !MUTATING.contains(method)) {
            return Optional.empty();
        }
        return CONFIG.stream().filter(r -> r.matches(pattern)).map(Rule::area).findFirst();
    }

    /** {@code /api/v1/sla/policies/{id}} → {@code sla/policies/{id}}. */
    static String objectType(String pattern) {
        if (pattern == null) {
            return null;
        }
        return pattern.startsWith("/api/v1/") ? pattern.substring("/api/v1/".length()) : pattern;
    }

    private record Rule(String prefix, String area, List<String> excluded) {
        boolean matches(String pattern) {
            return pattern.startsWith(prefix) && excluded.stream().noneMatch(pattern::contains);
        }
    }
}
