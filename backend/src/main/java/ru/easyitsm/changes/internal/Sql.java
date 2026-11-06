package ru.easyitsm.changes.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Мелкие преобразования для JdbcClient: время и массивы PostgreSQL. */
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

    static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    /** Литерал массива для {@code cast(:p as uuid[])}. */
    static String array(Collection<UUID> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<UUID> result = new ArrayList<>(values.length);
        for (Object v : values) {
            result.add(v instanceof UUID u ? u : UUID.fromString(v.toString()));
        }
        return List.copyOf(result);
    }
}
