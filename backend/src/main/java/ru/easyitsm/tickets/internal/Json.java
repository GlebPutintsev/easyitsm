package ru.easyitsm.tickets.internal;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Преобразование JSONB-колонок и документов настроек. */
@Component
class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    Map<String, Object> readMap(String json) {
        return json == null || json.isBlank() ? new java.util.LinkedHashMap<>() : mapper.readValue(json, MAP);
    }

    List<Object> readList(String json) {
        return json == null || json.isBlank() ? new java.util.ArrayList<>() : mapper.readValue(json, LIST);
    }

    <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }
}
