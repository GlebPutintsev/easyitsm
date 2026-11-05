package ru.easyitsm.tickets.internal;

import java.util.List;
import java.util.UUID;

/**
 * Общие настройки заявок арендатора.
 *
 * @param defaultGroupId               группа по умолчанию, последнее правило 5.8
 * @param autoCloseBusinessDays        автозакрытие решённых через N рабочих дней (INC-07)
 * @param resolveRequiresClosedSubtasks решение запрещено, пока открыты подзадачи
 * @param identityCheckCategoryIds     категории, где решение требует проверки личности (INC-10)
 */
record GeneralSettings(UUID defaultGroupId, Integer autoCloseBusinessDays, Boolean resolveRequiresClosedSubtasks,
                       List<UUID> identityCheckCategoryIds, Long version) {

    GeneralSettings {
        autoCloseBusinessDays = autoCloseBusinessDays == null ? 5 : autoCloseBusinessDays;
        resolveRequiresClosedSubtasks = resolveRequiresClosedSubtasks == null || resolveRequiresClosedSubtasks;
        identityCheckCategoryIds = identityCheckCategoryIds == null ? List.of() : List.copyOf(identityCheckCategoryIds);
    }

    static GeneralSettings defaults() {
        return new GeneralSettings(null, 5, true, List.of(), null);
    }

    GeneralSettings withVersion(Long v) {
        return new GeneralSettings(defaultGroupId, autoCloseBusinessDays, resolveRequiresClosedSubtasks,
                identityCheckCategoryIds, v);
    }
}
