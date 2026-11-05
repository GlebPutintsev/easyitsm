package ru.easyitsm.tickets.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Сохранённые представления очереди (TCK-16) и готовые ответы (TCK-09).
 * Видимость: личное — владелец; групповое — участники группы (пока членство неизвестно — вся поддержка);
 * общее — все агенты. Создать групповое может LEAD или ADM, общее — ADM.
 */
@Service
class SavedViews {

    private final JdbcClient jdbc;
    private final Json json;

    SavedViews(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record ViewDef(UUID id, String name, String visibility, UUID groupId, UUID ownerId, Map<String, Object> filter,
                   String sort, List<Map<String, Object>> columns, Integer position, Long version) {
    }

    record Template(UUID id, String name, String body, String visibility, UUID groupId, List<String> ticketTypes,
                    UUID ownerId, Long version) {
    }

    // ---------------------------------------------------------------- представления

    @Transactional(readOnly = true)
    List<ViewDef> views(Actor actor) {
        requireAgent(actor);
        return jdbc.sql("""
                select id, name, visibility, group_id, owner_id, filter::text as filter, sort, columns::text as columns,
                       position, version
                  from ticket_view
                 where owner_id = :me or visibility in ('group', 'shared')
                 order by position, name, id""")
                .param("me", actor.userId())
                .query((rs, n) -> new ViewDef(Sql.uuid(rs, "id"), rs.getString("name"), rs.getString("visibility"),
                        Sql.uuid(rs, "group_id"), Sql.uuid(rs, "owner_id"), json.readMap(rs.getString("filter")),
                        rs.getString("sort"), columns(rs.getString("columns")), rs.getInt("position"),
                        rs.getLong("version")))
                .list();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> columns(String raw) {
        return json.readList(raw).stream().map(o -> (Map<String, Object>) o).toList();
    }

    private ViewDef view(UUID id, Actor actor) {
        return views(actor).stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Представление"));
    }

    @Transactional
    ViewDef createView(ViewDef v, Actor actor) {
        requireAgent(actor);
        validate(v.name(), v.visibility(), v.groupId(), actor);
        if (v.sort() != null && !TicketSearch.SORTS.contains(v.sort())) {
            throw TicketProblem.fieldRequired("sort", "Неизвестная сортировка");
        }
        UUID id = Ids.newId();
        var now = Sql.ts(Sql.now());
        jdbc.sql("""
                insert into ticket_view (id, tenant_id, owner_id, name, visibility, group_id, filter, sort, columns,
                    position, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :me, :name, :visibility, :group, cast(:filter as jsonb), :sort,
                    cast(:columns as jsonb), :position, :now, :me, :now, :me, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("me", actor.userId())
                .param("name", v.name().strip()).param("visibility", v.visibility()).param("group", v.groupId())
                .param("filter", json.write(v.filter() == null ? Map.of() : v.filter())).param("sort", v.sort())
                .param("columns", json.write(v.columns() == null ? List.of() : v.columns()))
                .param("position", v.position() == null ? 0 : v.position()).param("now", now)
                .update();
        return view(id, actor);
    }

    @Transactional
    ViewDef updateView(UUID id, String ifMatch, ViewDef patch, Actor actor) {
        ViewDef cur = view(id, actor);
        requireOwnerOrManager(cur.ownerId(), cur.visibility(), actor);
        ETags.requireMatch(ifMatch, cur.version());
        String visibility = patch.visibility() != null ? patch.visibility() : cur.visibility();
        UUID group = patch.groupId() != null ? patch.groupId() : cur.groupId();
        String name = patch.name() != null ? patch.name() : cur.name();
        validate(name, visibility, group, actor);
        jdbc.sql("""
                update ticket_view set name = :name, visibility = :visibility, group_id = :group,
                       filter = cast(:filter as jsonb), sort = :sort, columns = cast(:columns as jsonb),
                       position = :position, updated_at = :now, updated_by = :me, version = version + 1
                 where id = :id and version = :version""")
                .param("name", name.strip()).param("visibility", visibility).param("group", group)
                .param("filter", json.write(patch.filter() != null ? patch.filter() : cur.filter()))
                .param("sort", patch.sort() != null ? patch.sort() : cur.sort())
                .param("columns", json.write(patch.columns() != null ? patch.columns() : cur.columns()))
                .param("position", patch.position() != null ? patch.position() : cur.position())
                .param("now", Sql.ts(Sql.now())).param("me", actor.userId()).param("id", id)
                .param("version", cur.version())
                .update();
        return view(id, actor);
    }

    @Transactional
    void deleteView(UUID id, Actor actor) {
        ViewDef cur = view(id, actor);
        requireOwnerOrManager(cur.ownerId(), cur.visibility(), actor);
        jdbc.sql("delete from ticket_view where id = :id").param("id", id).update();
    }

    // ---------------------------------------------------------------- готовые ответы

    @Transactional(readOnly = true)
    List<Template> templates(String q, Actor actor) {
        requireAgent(actor);
        return jdbc.sql("""
                select id, name, body, visibility, group_id, ticket_types, owner_id, version
                  from ticket_response_template
                 where (owner_id = :me or visibility in ('group', 'shared'))
                   and (cast(:q as text) is null or name ilike '%' || :q || '%')
                 order by name, id""")
                .param("me", actor.userId()).param("q", q == null || q.isBlank() ? null : q.strip())
                .query((rs, n) -> new Template(Sql.uuid(rs, "id"), rs.getString("name"), rs.getString("body"),
                        rs.getString("visibility"), Sql.uuid(rs, "group_id"), Sql.strings(rs, "ticket_types"),
                        Sql.uuid(rs, "owner_id"), rs.getLong("version")))
                .list();
    }

    private Template template(UUID id, Actor actor) {
        return templates(null, actor).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Готовый ответ"));
    }

    @Transactional
    Template createTemplate(Template t, Actor actor) {
        requireAgent(actor);
        validate(t.name(), t.visibility(), t.groupId(), actor);
        if (t.body() == null || t.body().isBlank()) {
            throw TicketProblem.fieldRequired("body", "Укажите текст");
        }
        UUID id = Ids.newId();
        var now = Sql.ts(Sql.now());
        jdbc.sql("""
                insert into ticket_response_template (id, tenant_id, owner_id, name, body, visibility, group_id,
                    ticket_types, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :me, :name, :body, :visibility, :group, cast(:types as text[]), :now, :me, :now,
                    :me, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("me", actor.userId())
                .param("name", t.name().strip()).param("body", t.body()).param("visibility", t.visibility())
                .param("group", t.groupId()).param("types", Sql.array(t.ticketTypes())).param("now", now)
                .update();
        return template(id, actor);
    }

    @Transactional
    Template updateTemplate(UUID id, String ifMatch, Template patch, Actor actor) {
        Template cur = template(id, actor);
        requireOwnerOrManager(cur.ownerId(), cur.visibility(), actor);
        ETags.requireMatch(ifMatch, cur.version());
        String visibility = patch.visibility() != null ? patch.visibility() : cur.visibility();
        UUID group = patch.groupId() != null ? patch.groupId() : cur.groupId();
        String name = patch.name() != null ? patch.name() : cur.name();
        validate(name, visibility, group, actor);
        jdbc.sql("""
                update ticket_response_template set name = :name, body = :body, visibility = :visibility,
                       group_id = :group, ticket_types = cast(:types as text[]), updated_at = :now, updated_by = :me,
                       version = version + 1
                 where id = :id and version = :version""")
                .param("name", name.strip()).param("body", patch.body() != null ? patch.body() : cur.body())
                .param("visibility", visibility).param("group", group)
                .param("types", Sql.array(patch.ticketTypes() != null ? patch.ticketTypes() : cur.ticketTypes()))
                .param("now", Sql.ts(Sql.now())).param("me", actor.userId()).param("id", id)
                .param("version", cur.version())
                .update();
        return template(id, actor);
    }

    @Transactional
    void deleteTemplate(UUID id, Actor actor) {
        Template cur = template(id, actor);
        requireOwnerOrManager(cur.ownerId(), cur.visibility(), actor);
        jdbc.sql("delete from ticket_response_template where id = :id").param("id", id).update();
    }

    // ---------------------------------------------------------------- права

    private static void requireAgent(Actor actor) {
        if (!actor.isSupport() && !actor.isAdmin()) {
            throw ApiException.forbidden();
        }
    }

    private static void validate(String name, String visibility, UUID groupId, Actor actor) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw TicketProblem.fieldRequired("name", "Название — от 1 до 255 символов");
        }
        switch (visibility == null ? "" : visibility) {
            case "personal" -> {
            }
            case "group" -> {
                if (groupId == null) {
                    throw TicketProblem.fieldRequired("groupId", "Укажите группу");
                }
                if (!actor.has("LEAD") && !actor.isAdmin()) {
                    throw ApiException.forbidden();
                }
            }
            case "shared" -> {
                if (!actor.isAdmin()) {
                    throw ApiException.forbidden();
                }
            }
            default -> throw TicketProblem.fieldRequired("visibility", "Видимость: personal, group или shared");
        }
    }

    private static void requireOwnerOrManager(UUID owner, String visibility, Actor actor) {
        boolean ok = actor.is(owner)
                || ("group".equals(visibility) && (actor.has("LEAD") || actor.isAdmin()))
                || ("shared".equals(visibility) && actor.isAdmin());
        if (!ok) {
            throw ApiException.forbidden();
        }
    }
}
