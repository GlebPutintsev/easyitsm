package ru.easyitsm.cmdb.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSONB-колонки модуля: атрибуты КЕ, схема атрибутов и правила идентификации классов. */
@Component
class CmdbJson {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<AttributeDef>> ATTRIBUTES = new TypeReference<>() {
    };
    private static final TypeReference<List<List<String>>> RULES = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    CmdbJson(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    Map<String, Object> readMap(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : mapper.readValue(json, MAP);
    }

    /** Любое JSON-значение: строка, число, логическое, объект. */
    Object read(String json) {
        return json == null ? null : mapper.readValue(json, Object.class);
    }

    <T> T read(String json, TypeReference<T> type) {
        return json == null ? null : mapper.readValue(json, type);
    }

    List<AttributeDef> readAttributes(String json) {
        return json == null || json.isBlank() ? new ArrayList<>() : mapper.readValue(json, ATTRIBUTES);
    }

    List<List<String>> readRules(String json) {
        return json == null || json.isBlank() ? new ArrayList<>() : mapper.readValue(json, RULES);
    }
}
