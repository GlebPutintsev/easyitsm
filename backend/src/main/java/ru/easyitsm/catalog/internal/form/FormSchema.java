package ru.easyitsm.catalog.internal.form;

import java.util.List;

/** Схема формы предложения каталога (REQ-02). Хранится как JSON в {@code catalog_item.form}. */
public record FormSchema(List<FormField> fields) {

    public static final FormSchema EMPTY = new FormSchema(List.of());

    public FormSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
