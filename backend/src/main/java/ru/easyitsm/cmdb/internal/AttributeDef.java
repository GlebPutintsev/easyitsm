package ru.easyitsm.cmdb.internal;

import java.util.List;

/**
 * Атрибут класса КЕ (CFG-01).
 *
 * @param type    {@code string}, {@code text}, {@code number}, {@code boolean}, {@code date}, {@code ip},
 *                {@code enum}
 * @param options значения для {@code enum}
 */
record AttributeDef(String code, String name, String type, Boolean required, List<String> options) {

    static final List<String> TYPES = List.of("string", "text", "number", "boolean", "date", "ip", "enum");

    AttributeDef {
        required = Boolean.TRUE.equals(required);
        options = options == null ? List.of() : List.copyOf(options);
    }

    static AttributeDef of(String code, String name, String type) {
        return new AttributeDef(code, name, type, false, List.of());
    }

    static AttributeDef required(String code, String name, String type) {
        return new AttributeDef(code, name, type, true, List.of());
    }
}
