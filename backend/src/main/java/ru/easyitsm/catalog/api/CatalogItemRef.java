package ru.easyitsm.catalog.api;

import java.util.UUID;

/** Краткое описание предложения каталога для поиска и ссылок из других модулей. */
public record CatalogItemRef(UUID id, String name, String summary, UUID categoryId, String kind) {
}
