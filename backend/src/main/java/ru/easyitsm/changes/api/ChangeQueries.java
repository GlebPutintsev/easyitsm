package ru.easyitsm.changes.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Чтение изменений другими модулями в пределах арендатора (RLS), без проверки прав пользователя. */
public interface ChangeQueries {

    Optional<ChangeSummary> find(UUID changeId);

    /** Изменения, затрагивающие КЕ, новые сверху, не больше {@code limit} (до 200). */
    List<ChangeSummary> byCi(UUID ciId, int limit);
}
