package ru.easyitsm.catalog.internal;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import ru.easyitsm.catalog.internal.form.FormSchema;
import ru.easyitsm.platform.web.ApiException;

/** JSON-колонки модуля: схема формы и шаги согласования. */
@Component
class JsonCodec {

    private static final TypeReference<List<StepDef>> STEPS = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper mapper;

    JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    FormSchema form(String json) {
        return read(json, FormSchema.class);
    }

    List<StepDef> steps(String json) {
        try {
            return mapper.readValue(json, STEPS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Повреждены шаги согласования", e);
        }
    }

    Map<String, Object> map(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JacksonException e) {
            throw new IllegalStateException("Повреждён JSON", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("Повреждён JSON " + type.getSimpleName(), e);
        }
    }

    /** Разбор пользовательского ввода: ошибка — 400, а не 500. */
    <T> T parseInput(Object input, Class<T> type, String code) {
        try {
            return mapper.convertValue(input, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw ApiException.badRequest(code, "Некорректная структура данных");
        }
    }
}
