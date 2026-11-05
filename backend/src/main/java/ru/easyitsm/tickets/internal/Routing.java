package ru.easyitsm.tickets.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Маршрутизация (раздел 5.8): прямое назначение → правила по порядку → распределение внутри группы
 * → группа по умолчанию. Подсказка ИИ (п. 3) появится с модулем ai-client.
 */
@Component
class Routing {

    private final JdbcClient jdbc;
    private final Json json;
    private final SettingsStore settings;

    Routing(JdbcClient jdbc, Json json, SettingsStore settings) {
        this.jdbc = jdbc;
        this.json = json;
        this.settings = settings;
    }

    record Conditions(List<String> ticketTypes, List<UUID> serviceIds, List<UUID> categoryIds, List<UUID> ciIds,
                      List<String> channels, List<String> priorities, List<String> keywords, String language) {

        static Conditions empty() {
            return new Conditions(null, null, null, null, null, null, null, null);
        }

        boolean matches(TicketRow t) {
            return in(ticketTypes, t.type.code()) && in(serviceIds, t.serviceId) && in(categoryIds, t.categoryId)
                    && in(ciIds, t.ciId) && in(channels, t.channel.code()) && in(priorities, t.priority.code())
                    && keywordsMatch(t.title) && languageMatches(t.title + " " + t.description);
        }

        private static <T> boolean in(List<T> allowed, T value) {
            return allowed == null || allowed.isEmpty() || (value != null && allowed.contains(value));
        }

        private boolean keywordsMatch(String title) {
            if (keywords == null || keywords.isEmpty()) {
                return true;
            }
            String lower = title.toLowerCase(Locale.ROOT);
            return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
        }

        private boolean languageMatches(String text) {
            if (language == null || language.isBlank()) {
                return true;
            }
            long cyr = text.chars().filter(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC).count();
            long lat = text.chars().filter(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')).count();
            String detected = cyr >= lat ? "ru" : "en";
            return detected.equals(language);
        }
    }

    record Rule(UUID id, String name, Integer position, Boolean enabled, Conditions conditions, UUID groupId,
                UUID assigneeId, Long version) {
    }

    record Dispatch(UUID groupId, String strategy, List<UUID> members, Long version) {
    }

    record Result(UUID groupId, UUID assigneeId, String source) {
    }

    /** Применяет маршрутизацию к новой заявке без группы. */
    Result route(TicketRow t) {
        UUID group = t.groupId;
        UUID assignee = t.assigneeId;
        String source = "direct";
        if (group == null) {
            for (Rule r : rules()) {
                if (!Boolean.FALSE.equals(r.enabled()) && r.conditions().matches(t)) {
                    group = r.groupId();
                    assignee = assignee != null ? assignee : r.assigneeId();
                    source = "rule:" + r.id();
                    break;
                }
            }
        }
        if (group == null) {
            group = settings.general().defaultGroupId();
            source = group == null ? "none" : "default_group";
        }
        if (group != null && assignee == null) {
            assignee = dispatch(group).orElse(null);
        }
        return new Result(group, assignee, source);
    }

    /** Исполнитель внутри группы по её стратегии или пусто. */
    Optional<UUID> dispatch(UUID groupId) {
        var row = jdbc.sql("select strategy, members, rr_cursor from ticket_group_dispatch where group_id = :g for update")
                .param("g", groupId)
                .query((rs, n) -> new Object[]{rs.getString("strategy"), Sql.uuids(rs, "members"), rs.getInt("rr_cursor")})
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String strategy = (String) row.get()[0];
        @SuppressWarnings("unchecked")
        List<UUID> members = (List<UUID>) row.get()[1];
        int cursor = (Integer) row.get()[2];
        if (members.isEmpty() || "none".equals(strategy)) {
            return Optional.empty();
        }
        if ("round_robin".equals(strategy)) {
            UUID next = members.get(Math.floorMod(cursor, members.size()));
            jdbc.sql("update ticket_group_dispatch set rr_cursor = :c where group_id = :g")
                    .param("c", (cursor + 1) % members.size()).param("g", groupId).update();
            return Optional.of(next);
        }
        // least_loaded: меньше всего активных заявок, при равенстве — порядок в списке
        Map<UUID, Long> load = new java.util.HashMap<>();
        jdbc.sql("""
                select assignee_id, count(*) as n from ticket
                 where assignee_id = any(cast(:m as uuid[]))
                   and status_category in ('new', 'assigned', 'in_progress', 'pending')
                 group by assignee_id""")
                .param("m", Sql.array(members))
                .query((rs, n) -> load.put(Sql.uuid(rs, "assignee_id"), rs.getLong("n")))
                .list();
        UUID best = members.getFirst();
        for (UUID m : members) {
            if (load.getOrDefault(m, 0L) < load.getOrDefault(best, 0L)) {
                best = m;
            }
        }
        return Optional.of(best);
    }

    List<Rule> rules() {
        return jdbc.sql("""
                select id, name, position, enabled, conditions::text as conditions, group_id, assignee_id, version
                  from ticket_routing_rule order by position, id""")
                .query((rs, n) -> new Rule(Sql.uuid(rs, "id"), rs.getString("name"), rs.getInt("position"),
                        rs.getBoolean("enabled"), json.read(rs.getString("conditions"), Conditions.class),
                        Sql.uuid(rs, "group_id"), Sql.uuid(rs, "assignee_id"), rs.getLong("version")))
                .list();
    }

    Rule rule(UUID id) {
        return rules().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Правило"));
    }

    Rule createRule(Rule r) {
        validate(r);
        UUID id = Ids.newId();
        var now = Sql.ts(Sql.now());
        jdbc.sql("""
                insert into ticket_routing_rule (id, tenant_id, name, position, enabled, conditions, group_id, assignee_id,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :name, :position, :enabled, cast(:conditions as jsonb), :group, :assignee,
                    :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("name", r.name())
                .param("position", r.position() == null ? 0 : r.position())
                .param("enabled", !Boolean.FALSE.equals(r.enabled()))
                .param("conditions", json.write(r.conditions() == null ? Conditions.empty() : r.conditions()))
                .param("group", r.groupId()).param("assignee", r.assigneeId())
                .param("now", now).param("actor", CurrentActor.userId().orElse(null))
                .update();
        return rule(id);
    }

    Rule updateRule(UUID id, String ifMatch, Rule r) {
        Rule current = rule(id);
        ETags.requireMatch(ifMatch, current.version());
        Rule merged = new Rule(id, r.name() != null ? r.name() : current.name(),
                r.position() != null ? r.position() : current.position(),
                r.enabled() != null ? r.enabled() : current.enabled(),
                r.conditions() != null ? r.conditions() : current.conditions(),
                r.groupId() != null ? r.groupId() : current.groupId(), r.assigneeId(), current.version());
        validate(merged);
        jdbc.sql("""
                update ticket_routing_rule set name = :name, position = :position, enabled = :enabled,
                       conditions = cast(:conditions as jsonb), group_id = :group, assignee_id = :assignee,
                       updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", merged.name()).param("position", merged.position()).param("enabled", merged.enabled())
                .param("conditions", json.write(merged.conditions())).param("group", merged.groupId())
                .param("assignee", merged.assigneeId()).param("now", Sql.ts(Sql.now()))
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).param("version", current.version())
                .update();
        return rule(id);
    }

    void deleteRule(UUID id) {
        if (jdbc.sql("delete from ticket_routing_rule where id = :id").param("id", id).update() == 0) {
            throw ApiException.notFound("Правило");
        }
    }

    private static void validate(Rule r) {
        if (r.name() == null || r.name().isBlank()) {
            throw TicketProblem.fieldRequired("name", "Укажите название правила");
        }
        if (r.groupId() == null) {
            throw TicketProblem.fieldRequired("groupId", "Укажите группу");
        }
    }

    Dispatch dispatchSettings(UUID groupId) {
        return jdbc.sql("select group_id, strategy, members, version from ticket_group_dispatch where group_id = :g")
                .param("g", groupId)
                .query((rs, n) -> new Dispatch(Sql.uuid(rs, "group_id"), rs.getString("strategy"),
                        Sql.uuids(rs, "members"), rs.getLong("version")))
                .optional()
                .orElse(new Dispatch(groupId, "none", List.of(), 0L));
    }

    Dispatch saveDispatch(UUID groupId, Dispatch d) {
        if (!List.of("none", "round_robin", "least_loaded").contains(d.strategy())) {
            throw TicketProblem.fieldRequired("strategy", "Стратегия: none, round_robin или least_loaded");
        }
        var now = Sql.ts(Sql.now());
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into ticket_group_dispatch (id, tenant_id, group_id, strategy, members, rr_cursor,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :g, :strategy, cast(:members as uuid[]), 0, :now, :actor, :now, :actor, 0)
                on conflict (tenant_id, group_id) do update set strategy = excluded.strategy, members = excluded.members,
                    rr_cursor = 0, updated_at = excluded.updated_at, updated_by = excluded.updated_by,
                    version = ticket_group_dispatch.version + 1""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("g", groupId)
                .param("strategy", d.strategy()).param("members", Sql.array(d.members() == null ? List.of() : d.members()))
                .param("now", now).param("actor", actor)
                .update();
        return dispatchSettings(groupId);
    }
}
