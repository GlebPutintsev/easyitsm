package ru.easyitsm.cmdb.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение КЕ другими модулями в пределах арендатора (RLS), без проверки прав пользователя: вызывающий модуль
 * сам решает, кому показать результат. Отсутствующая или чужая КЕ — пусто.
 */
public interface CiDirectory {

    Optional<CiRef> find(UUID ciId);

    /** КЕ по списку id в порядке запроса; отсутствующие пропускаются. До 1000 id за вызов. */
    List<CiRef> findAll(Collection<UUID> ciIds);

    /** Поиск по имени для выбора в формах: сначала точные совпадения, затем по алфавиту. */
    List<CiRef> search(CiSearch query);
}
