package ru.easyitsm.knowledge.api;

import java.util.Set;
import java.util.UUID;

/**
 * Аудитории текущего пользователя (KB-04): статья видна при {@code everything}, аудитории {@code all},
 * {@code employees} для сотрудника, {@code agents} для сотрудника ИТ, {@code group} для участника группы.
 */
public record ArticleReadScope(boolean everything, boolean employee, boolean staff, Set<UUID> groupIds) {

    public ArticleReadScope {
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    public boolean canRead(ArticleSearchDocument d) {
        if (everything) {
            return true;
        }
        return switch (d.audience()) {
            case ALL -> true;
            case EMPLOYEES -> employee;
            case AGENTS -> staff;
            case GROUP -> d.audienceGroupId() != null && groupIds.contains(d.audienceGroupId());
        };
    }
}
