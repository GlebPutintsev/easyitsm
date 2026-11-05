package ru.easyitsm.tickets.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.TicketType;

/** Настраиваемые поля (TCK-07): определения и проверка значений в {@code ticket.custom}. */
@Component
class CustomFields {

    static final Set<String> TYPES = Set.of("text", "number", "date", "list", "user", "group", "ci", "checkbox", "file");
    private static final int MAX_TEXT = 10_000;

    private final JdbcClient jdbc;
    private final Json json;

    CustomFields(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Option(String value, String label) {
    }

    record Field(UUID id, String code, String name, String fieldType, List<Option> options, Boolean multiple,
                 Boolean required, List<String> ticketTypes, Boolean active, Integer position, Long version) {

        boolean appliesTo(TicketType type) {
            return ticketTypes == null || ticketTypes.isEmpty() || ticketTypes.contains(type.code());
        }
    }

    List<Field> list(TicketType type) {
        return jdbc.sql("""
                select id, code, name, field_type, options::text as options, multiple, required, ticket_types, active,
                       position, version
                  from ticket_custom_field order by position, code""")
                .query((rs, n) -> new Field(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("field_type"),
                        json.readList(rs.getString("options")).stream().map(o -> json.convert(o, Option.class)).toList(),
                        rs.getBoolean("multiple"), rs.getBoolean("required"), Sql.strings(rs, "ticket_types"),
                        rs.getBoolean("active"), rs.getInt("position"), rs.getLong("version")))
                .list().stream()
                .filter(f -> type == null || f.appliesTo(type))
                .toList();
    }

    Field get(UUID id) {
        return list(null).stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Поле"));
    }

    Field create(Field f) {
        validateDefinition(f);
        UUID id = Ids.newId();
        var now = Sql.ts(Sql.now());
        jdbc.sql("""
                insert into ticket_custom_field (id, tenant_id, code, name, field_type, options, multiple, required,
                    ticket_types, active, position, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :code, :name, :type, cast(:options as jsonb), :multiple, :required,
                    cast(:types as text[]), :active, :position, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("code", f.code())
                .param("name", f.name()).param("type", f.fieldType())
                .param("options", json.write(f.options() == null ? List.of() : f.options()))
                .param("multiple", Boolean.TRUE.equals(f.multiple())).param("required", Boolean.TRUE.equals(f.required()))
                .param("types", Sql.array(f.ticketTypes())).param("active", f.active() == null || f.active())
                .param("position", f.position() == null ? 0 : f.position())
                .param("now", now).param("actor", CurrentActor.userId().orElse(null))
                .update();
        return get(id);
    }

    /** Код и тип не меняются: значения в заявках уже записаны под ними. */
    Field update(UUID id, String ifMatch, Field patch) {
        Field current = get(id);
        ETags.requireMatch(ifMatch, current.version());
        Field merged = new Field(id, current.code(), patch.name() != null ? patch.name() : current.name(),
                current.fieldType(), patch.options() != null ? patch.options() : current.options(),
                current.multiple(), patch.required() != null ? patch.required() : current.required(),
                patch.ticketTypes() != null ? patch.ticketTypes() : current.ticketTypes(),
                patch.active() != null ? patch.active() : current.active(),
                patch.position() != null ? patch.position() : current.position(), current.version());
        validateDefinition(merged);
        jdbc.sql("""
                update ticket_custom_field set name = :name, options = cast(:options as jsonb), required = :required,
                       ticket_types = cast(:types as text[]), active = :active, position = :position,
                       updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", merged.name()).param("options", json.write(merged.options()))
                .param("required", merged.required()).param("types", Sql.array(merged.ticketTypes()))
                .param("active", merged.active()).param("position", merged.position())
                .param("now", Sql.ts(Sql.now())).param("actor", CurrentActor.userId().orElse(null))
                .param("id", id).param("version", current.version())
                .update();
        return get(id);
    }

    private static void validateDefinition(Field f) {
        if (f.code() == null || !f.code().matches("^[a-z][a-z0-9_]{1,62}$")) {
            throw TicketProblem.fieldRequired("code", "Код поля: латиница в нижнем регистре, цифры и _, от 2 символов");
        }
        if (f.name() == null || f.name().isBlank()) {
            throw TicketProblem.fieldRequired("name", "Укажите название поля");
        }
        if (!TYPES.contains(f.fieldType())) {
            throw TicketProblem.fieldRequired("fieldType", "Неизвестный тип поля");
        }
        if ("list".equals(f.fieldType()) && (f.options() == null || f.options().isEmpty())) {
            throw TicketProblem.fieldRequired("options", "У списка должен быть хотя бы один вариант");
        }
        if (f.ticketTypes() != null) {
            f.ticketTypes().forEach(TicketType::fromCode);
        }
    }

    /**
     * Проверяет и нормализует значения. {@code current} — уже сохранённые значения (при изменении),
     * {@code patch} — новые; null в значении удаляет поле.
     *
     * @return итоговая карта значений
     */
    Map<String, Object> apply(TicketType type, Map<String, Object> current, Map<String, Object> patch, boolean creating) {
        Map<String, Field> defs = new LinkedHashMap<>();
        for (Field f : list(type)) {
            if (Boolean.TRUE.equals(f.active())) {
                defs.put(f.code(), f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (patch != null) {
            for (var e : patch.entrySet()) {
                Field f = defs.get(e.getKey());
                if (f == null) {
                    throw TicketProblem.invalidCustom("custom." + e.getKey(), "Поле не существует или не используется для этого типа");
                }
                if (e.getValue() == null) {
                    result.remove(e.getKey());
                } else {
                    result.put(e.getKey(), normalize(f, e.getValue()));
                }
            }
        }
        if (creating || patch != null) {
            for (Field f : defs.values()) {
                if (Boolean.TRUE.equals(f.required()) && isEmpty(result.get(f.code()))) {
                    throw TicketProblem.invalidCustom("custom." + f.code(), "Заполните поле «" + f.name() + "»");
                }
            }
        }
        return result;
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
    }

    private Object normalize(Field f, Object value) {
        String field = "custom." + f.code();
        if (Boolean.TRUE.equals(f.multiple()) && Set.of("list", "user", "group", "ci", "file").contains(f.fieldType())) {
            if (!(value instanceof Collection<?> values)) {
                throw TicketProblem.invalidCustom(field, "Ожидается список значений");
            }
            return values.stream().map(v -> single(f, v, field)).distinct().toList();
        }
        return single(f, value, field);
    }

    private static Object single(Field f, Object v, String field) {
        try {
            return switch (f.fieldType()) {
                case "text" -> {
                    String s = (String) v;
                    if (s.length() > MAX_TEXT) {
                        throw TicketProblem.invalidCustom(field, "Слишком длинный текст");
                    }
                    yield s;
                }
                case "number" -> {
                    if (v instanceof Number n) {
                        yield n;
                    }
                    yield new java.math.BigDecimal(v.toString());
                }
                case "date" -> {
                    String s = v.toString();
                    if (s.length() == 10) {
                        LocalDate.parse(s);
                    } else {
                        OffsetDateTime.parse(s);
                    }
                    yield s;
                }
                case "checkbox" -> {
                    if (!(v instanceof Boolean)) {
                        throw TicketProblem.invalidCustom(field, "Ожидается true или false");
                    }
                    yield v;
                }
                case "list" -> {
                    String s = v.toString();
                    if (f.options().stream().noneMatch(o -> o.value().equals(s))) {
                        throw TicketProblem.invalidCustom(field, "Значения нет в списке вариантов");
                    }
                    yield s;
                }
                default -> UUID.fromString(v.toString()).toString(); // user, group, ci, file
            };
        } catch (TicketProblem e) {
            throw e;
        } catch (RuntimeException e) {
            throw TicketProblem.invalidCustom(field, "Значение не подходит к типу поля «" + f.fieldType() + "»");
        }
    }
}
