package ru.easyitsm.admin.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON для колонок jsonb и общие преобразования строк результата. */
@Component
class AdminJson {

    private final JsonMapper mapper;

    AdminJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** Снимок как обычные Map, List, String, Number, Boolean и null. */
    Object tree(String json) {
        return json == null || json.isBlank() ? null : mapper.readValue(json, Object.class);
    }

    <T> T read(String json, TypeReference<T> type) {
        return mapper.readValue(json, type);
    }

    <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
