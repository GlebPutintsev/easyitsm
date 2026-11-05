package ru.easyitsm.catalog.api;

import java.util.List;

/** Чтение каталога другими модулями, например глобальным поиском (PRT-02). Учитывает права текущего пользователя. */
public interface CatalogQueries {

    /** Видимые текущему пользователю активные предложения, подходящие под строку поиска. */
    List<CatalogItemRef> search(String query, int limit);
}
