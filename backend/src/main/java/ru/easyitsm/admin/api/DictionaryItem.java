package ru.easyitsm.admin.api;

/** Значение справочника: код хранится в записях других модулей и не меняется, название показывается людям. */
public record DictionaryItem(String code, String name, boolean active) {
}
