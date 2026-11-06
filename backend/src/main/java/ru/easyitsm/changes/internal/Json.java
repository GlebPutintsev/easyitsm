package ru.easyitsm.changes.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSONB-колонки модуля. */
@Component("changesJson")
class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    Map<String, Object> map(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : mapper.readValue(json, MAP);
    }

    List<Map<String, Object>> list(String json) {
        return json == null || json.isBlank() ? new ArrayList<>() : mapper.readValue(json, LIST);
    }

    <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }
}
