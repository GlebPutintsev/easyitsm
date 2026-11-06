package ru.easyitsm.cmdb.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Мелкие преобразования для JdbcClient. */
final class Sql {

    private Sql() {
    }

    /** Текущее время с точностью PostgreSQL. */
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

    /** Экранирует {@code %}, {@code _} и {@code \} для LIKE. */
    static String likeContains(String text) {
        return "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
