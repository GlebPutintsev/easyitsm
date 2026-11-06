package ru.easyitsm.cmdb.api;

import java.util.Set;

/**
 * Запрос поиска КЕ для выбора в формах.
 *
 * @param text           часть имени (без учёта регистра) или точный id; пусто — без фильтра
 * @param classCodes     классы с подклассами: {@code server} найдёт и {@code virtual_machine}; пусто — любые
 * @param layers         уровни сервисной модели; пусто — любые
 * @param includeRetired показывать выведенные из эксплуатации
 * @param limit          1…200
 */
public record CiSearch(String text, Set<String> classCodes, Set<CiLayer> layers, boolean includeRetired,
                       int limit) {

    public CiSearch {
        classCodes = classCodes == null ? Set.of() : Set.copyOf(classCodes);
        layers = layers == null ? Set.of() : Set.copyOf(layers);
        limit = Math.clamp(limit, 1, 200);
    }

    public static CiSearch text(String text, int limit) {
        return new CiSearch(text, Set.of(), Set.of(), false, limit);
    }

    /** Только услуги (бизнес и технические), например для поля «Услуга» заявки. */
    public static CiSearch services(String text, int limit) {
        return new CiSearch(text, Set.of(), Set.of(CiLayer.BUSINESS_SERVICE, CiLayer.TECHNICAL_SERVICE), false,
                limit);
    }
}
