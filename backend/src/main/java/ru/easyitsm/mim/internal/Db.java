package ru.easyitsm.mim.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/** Мелкие преобразования для JdbcClient. */
final class Db {

    private Db() {
    }

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

    static String array(Collection<UUID> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }
}
