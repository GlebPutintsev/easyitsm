package ru.easyitsm.tickets.internal;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketType;

/** Настройки заявок для администратора: циклы, матрица приоритета, общие параметры, категории. */
@Service
class TicketSettings {

    private final SettingsStore store;
    private final TicketStore tickets;
    private final JdbcClient jdbc;

    TicketSettings(SettingsStore store, TicketStore tickets, JdbcClient jdbc) {
        this.store = store;
        this.tickets = tickets;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<Workflow> workflows() {
        return Arrays.stream(TicketType.values()).map(store::workflow).toList();
    }

    @Transactional(readOnly = true)
    Workflow workflow(TicketType type) {
        return store.workflow(type);
    }

    @Transactional
    Workflow saveWorkflow(TicketType type, String ifMatch, Workflow wf) {
        Workflow current = store.workflow(type);
        Workflow next = new Workflow(type.code(), wf.statuses(), wf.transitions(), null);
        validate(next);
        Set<String> kept = new HashSet<>();
        next.statuses().forEach(s -> kept.add(s.code()));
        for (Workflow.Status s : current.statuses()) {
            if (!kept.contains(s.code()) && tickets.countInStatus(type, s.code()) > 0) {
                throw TicketProblem.clash("status_in_use", "В статусе «" + s.name() + "» есть заявки")
                        .with("field", s.code());
            }
        }
        long version = store.save("workflow." + type.code(), next, ifMatch, current.version());
        return next.withVersion(version);
    }

    static void validate(Workflow wf) {
        if (wf.statuses().isEmpty()) {
            throw TicketProblem.fieldRequired("statuses", "Нужен хотя бы один статус");
        }
        Set<String> codes = new HashSet<>();
        int initial = 0;
        for (Workflow.Status s : wf.statuses()) {
            if (s.code() == null || !s.code().matches("^[a-z][a-z0-9_]{1,39}$")) {
                throw TicketProblem.fieldRequired("statuses.code", "Код статуса: латиница, цифры и _, от 2 символов");
            }
            if (!codes.add(s.code())) {
                throw TicketProblem.fieldRequired("statuses.code", "Код статуса повторяется: " + s.code());
            }
            if (s.name() == null || s.name().isBlank()) {
                throw TicketProblem.fieldRequired("statuses.name", "Укажите название статуса " + s.code());
            }
            try {
                s.categoryEnum();
            } catch (IllegalArgumentException e) {
                throw TicketProblem.fieldRequired("statuses.category", e.getMessage());
            }
            if (Boolean.TRUE.equals(s.initial())) {
                initial++;
            }
        }
        if (initial != 1) {
            throw TicketProblem.fieldRequired("statuses.initial", "Ровно один статус должен быть начальным");
        }
        for (Workflow.Transition t : wf.transitions()) {
            if (!codes.contains(t.from()) || !codes.contains(t.to())) {
                throw TicketProblem.fieldRequired("transitions", "Переход ссылается на несуществующий статус: "
                        + t.from() + " → " + t.to());
            }
            if (!Workflow.TRIGGERS.contains(t.trigger())) {
                throw TicketProblem.fieldRequired("transitions.trigger", "Неизвестный триггер: " + t.trigger());
            }
            if (!Workflow.REQUIRED_FIELDS.containsAll(t.requiredFields())) {
                throw TicketProblem.fieldRequired("transitions.requiredFields", "Неизвестное обязательное поле");
            }
            if (t.manual() && t.roles().isEmpty()) {
                throw TicketProblem.fieldRequired("transitions.roles", "У ручного перехода должны быть роли: "
                        + t.from() + " → " + t.to());
            }
        }
        // Из каждого статуса достижим финальный: заявка не может застрять навсегда.
        for (Workflow.Status s : wf.statuses()) {
            if (!reachesFinal(wf, s.code())) {
                throw TicketProblem.fieldRequired("transitions", "Из статуса «" + s.name() + "» нельзя завершить заявку");
            }
        }
    }

    private static boolean reachesFinal(Workflow wf, String from) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String code = queue.poll();
            if (!seen.add(code)) {
                continue;
            }
            if (wf.require(code).categoryEnum().isFinal()) {
                return true;
            }
            wf.from(code).forEach(t -> queue.add(t.to()));
        }
        return false;
    }

    @Transactional(readOnly = true)
    PriorityMatrix matrix() {
        return store.priorityMatrix();
    }

    @Transactional
    PriorityMatrix saveMatrix(String ifMatch, PriorityMatrix m) {
        m.validate();
        long v = store.save("priority_matrix", m.withVersion(null), ifMatch, store.priorityMatrix().version());
        return m.withVersion(v);
    }

    @Transactional(readOnly = true)
    GeneralSettings general() {
        return store.general();
    }

    @Transactional
    GeneralSettings saveGeneral(String ifMatch, GeneralSettings g) {
        if (g.autoCloseBusinessDays() < 1 || g.autoCloseBusinessDays() > 60) {
            throw TicketProblem.fieldRequired("autoCloseBusinessDays", "От 1 до 60 рабочих дней");
        }
        long v = store.save("general", g.withVersion(null), ifMatch, store.general().version());
        return g.withVersion(v);
    }

    // ---------------------------------------------------------------- категории

    record Category(UUID id, UUID parentId, String name, List<String> ticketTypes, Boolean active, Long version) {
    }

    @Transactional(readOnly = true)
    List<Category> categories() {
        return jdbc.sql("select id, parent_id, name, ticket_types, active, version from ticket_category order by name, id")
                .query((rs, n) -> new Category(Sql.uuid(rs, "id"), Sql.uuid(rs, "parent_id"), rs.getString("name"),
                        Sql.strings(rs, "ticket_types"), rs.getBoolean("active"), rs.getLong("version")))
                .list();
    }

    private Category category(UUID id) {
        return categories().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Категория"));
    }

    @Transactional
    Category createCategory(Category c) {
        if (c.name() == null || c.name().isBlank()) {
            throw TicketProblem.fieldRequired("name", "Укажите название категории");
        }
        if (c.parentId() != null) {
            category(c.parentId());
        }
        if (c.ticketTypes() != null) {
            c.ticketTypes().forEach(TicketType::fromCode);
        }
        UUID id = Ids.newId();
        var now = Sql.ts(Sql.now());
        jdbc.sql("""
                insert into ticket_category (id, tenant_id, parent_id, name, ticket_types, active, created_at, created_by,
                    updated_at, updated_by, version)
                values (:id, :tenant, :parent, :name, cast(:types as text[]), :active, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("parent", c.parentId())
                .param("name", c.name().strip()).param("types", Sql.array(c.ticketTypes()))
                .param("active", c.active() == null || c.active()).param("now", now)
                .param("actor", CurrentActor.userId().orElse(null))
                .update();
        return category(id);
    }

    @Transactional
    Category updateCategory(UUID id, String ifMatch, Category c) {
        Category current = category(id);
        ETags.requireMatch(ifMatch, current.version());
        if (c.parentId() != null && isDescendant(c.parentId(), id)) {
            throw TicketProblem.clash("link_cycle", "Категория не может быть вложена в свою подкатегорию");
        }
        jdbc.sql("""
                update ticket_category set parent_id = :parent, name = :name, ticket_types = cast(:types as text[]),
                       active = :active, updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("parent", c.parentId() != null ? c.parentId() : current.parentId())
                .param("name", c.name() != null ? c.name().strip() : current.name())
                .param("types", Sql.array(c.ticketTypes() != null ? c.ticketTypes() : current.ticketTypes()))
                .param("active", c.active() != null ? c.active() : current.active())
                .param("now", Sql.ts(Sql.now())).param("actor", CurrentActor.userId().orElse(null))
                .param("id", id).param("version", current.version())
                .update();
        return category(id);
    }

    private boolean isDescendant(UUID candidate, UUID root) {
        return jdbc.sql("""
                with recursive down(id) as (
                    select cast(:root as uuid)
                    union
                    select c.id from ticket_category c join down on c.parent_id = down.id
                )
                select exists (select 1 from down where id = :candidate)""")
                .param("root", root).param("candidate", candidate).query(Boolean.class).single();
    }

    /** Неизвестная категория в заявке — ошибка 400, чтобы не ссылаться на мусор. */
    void requireCategory(UUID id) {
        if (id != null && categories().stream().noneMatch(c -> c.id().equals(id))) {
            throw TicketProblem.fieldRequired("categoryId", "Категория не найдена");
        }
    }

    static boolean anyFinal(Workflow wf) {
        return wf.statuses().stream().anyMatch(s -> s.categoryEnum() == StatusCategory.CLOSED);
    }
}
