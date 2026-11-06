package ru.easyitsm.automation.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Значения, по которым проверяются условия: поля заявки из снимка и поля события. Значения приводятся к строкам
 * в нижнем регистре (коды перечислений, UUID, true/false), числа остаются числами, наборы — списками строк.
 * Поле, которого нет в событии, отсутствует в карте: при проверке правила вручную это «неизвестно».
 */
final class Facts {

    static final Set<String> TICKET_FIELDS = Set.of("type", "priority", "impact", "urgency", "statusCode",
            "statusCategory", "groupId", "assigneeId", "serviceId", "categoryId", "catalogItemId", "channel",
            "confidential", "title", "requesterId", "organizationId");

    /** Поля с UUID: значение условия проверяется как UUID. */
    static final Set<String> UUID_FIELDS = Set.of("groupId", "assigneeId", "serviceId", "categoryId", "catalogItemId",
            "requesterId", "organizationId", "fromGroupId", "fromAssigneeId");

    /** Числовые поля: для них есть сравнения gte и lte. */
    static final Set<String> NUMBER_FIELDS = Set.of("slaPercent", "escalationLevel");

    /** Поля-наборы: для них подходят contains и not_contains. */
    static final Set<String> LIST_FIELDS = Set.of("changedFields");

    private final Map<String, Object> values = new LinkedHashMap<>();

    private Facts() {
    }

    static Facts of(TicketSnapshot t) {
        Facts f = new Facts();
        f.put("type", t.type() == null ? null : t.type().code());
        f.put("priority", t.priority() == null ? null : t.priority().code());
        f.put("impact", t.impact() == null ? null : t.impact().code());
        f.put("urgency", t.urgency() == null ? null : t.urgency().code());
        f.put("statusCode", t.statusCode());
        f.put("statusCategory", t.statusCategory() == null ? null : t.statusCategory().code());
        f.put("groupId", t.groupId());
        f.put("assigneeId", t.assigneeId());
        f.put("serviceId", t.serviceId());
        f.put("categoryId", t.categoryId());
        f.put("catalogItemId", t.catalogItemId());
        f.put("channel", t.channel() == null ? null : t.channel().code());
        f.put("confidential", t.confidential());
        f.put("title", t.title());
        f.put("requesterId", t.requesterId());
        f.put("organizationId", t.organizationId());
        return f;
    }

    Facts put(String field, Object value) {
        values.put(field, normalize(value));
        return this;
    }

    boolean has(String field) {
        return values.containsKey(field);
    }

    Object get(String field) {
        return values.get(field);
    }

    Map<String, Object> asMap() {
        return java.util.Collections.unmodifiableMap(values);
    }

    static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(v -> v == null ? null : v.toString().toLowerCase(Locale.ROOT)).toList();
        }
        if (value instanceof UUID || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name().toLowerCase(Locale.ROOT);
        }
        return value.toString();
    }

    static List<String> strings(Object value) {
        if (value instanceof Collection<?> c) {
            return c.stream().map(v -> v == null ? "" : v.toString()).toList();
        }
        return value == null ? List.of() : List.of(value.toString());
    }
}
