package ru.easyitsm.cmdb.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ru.easyitsm.tickets.api.SecretScanner;

/**
 * Проверка атрибутов КЕ по схеме класса: тип, обязательность, значения списка. CFG-08: пароли, токены и ключи
 * в атрибутах и описании запрещены — значение отклоняется целиком, в ответе только код атрибута.
 */
@Component
class Attributes {

    /** Имена атрибутов, которые выдают секрет по смыслу (CFG-08). */
    static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(pass(word|wd)?|парол|secret|секрет|token|токен|api_?key|private_?key|credential|pin_?code).*");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/\\d{1,2})?$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}(/\\d{1,3})?$");
    private static final Pattern MAC = Pattern.compile("^[0-9A-F]{2}([:-]?[0-9A-F]{2}){5}$");
    private static final int MAX_STRING = 1000;
    private static final int MAX_TEXT = 10_000;

    private final SecretScanner secrets;

    Attributes(SecretScanner secrets) {
        this.secrets = secrets;
    }

    /**
     * Применяет изменения к атрибутам: {@code null} удаляет атрибут. Возвращает новую карту в порядке схемы класса.
     *
     * @param requireAll проверить обязательные атрибуты (создание, смена класса, любое изменение)
     */
    Map<String, Object> apply(List<AttributeDef> schema, Map<String, Object> current, Map<String, Object> changes) {
        Map<String, AttributeDef> defs = new LinkedHashMap<>();
        schema.forEach(d -> defs.put(d.code(), d));
        Map<String, Object> merged = new LinkedHashMap<>(current);
        if (changes != null) {
            for (Map.Entry<String, Object> e : changes.entrySet()) {
                if (e.getValue() == null || "".equals(e.getValue())) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            AttributeDef def = defs.get(e.getKey());
            if (def == null) {
                throw invalid("unknown_attribute", e.getKey(), "Атрибута «" + e.getKey() + "» нет в классе КЕ");
            }
        }
        for (AttributeDef def : schema) {
            Object value = merged.get(def.code());
            if (value == null) {
                if (def.required()) {
                    throw invalid("attribute_required", def.code(), "Заполните атрибут «" + def.name() + "»");
                }
                continue;
            }
            out.put(def.code(), normalize(def, value));
        }
        return out;
    }

    /** CFG-08: секрет в тексте (описание, имя) — 400 без значения в ответе. */
    void rejectSecrets(String field, String text) {
        if (text != null && !text.isEmpty() && !secrets.scan(text).isEmpty()) {
            throw invalid("secret_not_allowed", field,
                    "В CMDB нельзя хранить пароли, токены и ключи (CFG-08): уберите секрет из поля");
        }
    }

    private Object normalize(AttributeDef def, Object value) {
        String code = def.code();
        Object out = switch (def.type()) {
            case "number" -> {
                if (value instanceof Number num) {
                    yield num;
                }
                throw typeError(def, "число");
            }
            case "boolean" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                throw typeError(def, "да или нет");
            }
            case "date" -> {
                try {
                    yield LocalDate.parse(asString(def, value)).toString();
                } catch (DateTimeParseException e) {
                    throw typeError(def, "дата ГГГГ-ММ-ДД");
                }
            }
            case "ip" -> {
                String ip = asString(def, value).trim();
                if (!IPV4.matcher(ip).matches() && !(ip.contains(":") && IPV6.matcher(ip).matches())) {
                    throw typeError(def, "IP-адрес");
                }
                yield ip.toLowerCase(Locale.ROOT);
            }
            case "enum" -> {
                String s = asString(def, value);
                if (!def.options().contains(s)) {
                    throw typeError(def, "одно из значений " + def.options());
                }
                yield s;
            }
            case "text" -> limit(def, asString(def, value), MAX_TEXT);
            default -> limit(def, asString(def, value).trim(), MAX_STRING);
        };
        if (out instanceof String s) {
            rejectSecrets("attributes." + code, s);
            out = identifier(code, s);
        }
        return out;
    }

    /** Идентификаторы в одном написании, чтобы сверка и поиск дубликатов их сравнивали (CFG-05, CFG-07). */
    static String identifier(String code, String value) {
        return switch (code) {
            case "hostname", "domain", "fqdn", "url", "api_url" -> value.trim().toLowerCase(Locale.ROOT);
            case "mac_address" -> {
                String mac = value.trim().toUpperCase(Locale.ROOT);
                yield MAC.matcher(mac).matches()
                        ? String.join(":", mac.replaceAll("[:-]", "").split("(?<=\\G..)"))
                        : mac;
            }
            case "serial_number" -> value.trim().toUpperCase(Locale.ROOT);
            default -> value;
        };
    }

    private static String asString(AttributeDef def, Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw typeError(def, "строка");
    }

    private static String limit(AttributeDef def, String value, int max) {
        if (value.length() > max) {
            throw invalid("attribute_too_long", def.code(), "Атрибут «" + def.name() + "» длиннее " + max);
        }
        return value;
    }

    private static CmdbProblem typeError(AttributeDef def, String expected) {
        return invalid("invalid_attribute", def.code(), "Атрибут «" + def.name() + "»: ожидается " + expected);
    }

    static CmdbProblem invalid(String code, String field, String detail) {
        return CmdbProblem.invalid(code, field, detail);
    }
}
