package ru.easyitsm.tickets.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/** Мелкие преобразования для JdbcClient: время, массивы PostgreSQL. */
final class Sql {

    private Sql() {
    }

    /** Текущее время с точностью PostgreSQL, чтобы сравнения после чтения из БД совпадали. */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Литерал массива для {@code cast(:p as uuid[])} или {@code text[]}. Значения — UUID и коды без кавычек. */
    static String array(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    static java.util.List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return java.util.List.of();
        }
        Object[] values = (Object[]) array.getArray();
        java.util.List<UUID> result = new java.util.ArrayList<>(values.length);
        for (Object v : values) {
            result.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
        }
        return result;
    }

    static java.util.List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return java.util.List.of();
        }
        Object[] values = (Object[]) array.getArray();
        java.util.List<String> result = new java.util.ArrayList<>(values.length);
        for (Object v : values) {
            result.add(v.toString());
        }
        return result;
    }
}
