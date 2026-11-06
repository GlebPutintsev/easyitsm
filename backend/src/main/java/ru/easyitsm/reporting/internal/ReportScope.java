package ru.easyitsm.reporting.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/**
 * Область отчётов текущего пользователя.
 * <ul>
 *   <li>Показатели всего арендатора: аналитик (ANL), менеджер крупных инцидентов (MIM), аудитор (AUD) и
 *       администратор (ADM). Показатели — это числа без содержимого заявок.</li>
 *   <li>Показатели своих групп: агент (AGT) и руководитель группы (LEAD, «видит показатели группы»).</li>
 *   <li>Провал в список заявок: ANL, MIM, AUD — все неконфиденциальные заявки; AGT и LEAD — заявки своих групп
 *       и назначенные на них. Администратор без роли поддержки заявки не читает (матрица 3.2), поэтому видит
 *       только числа. Конфиденциальные заявки в список не попадают никогда: их карточку открывают в модуле
 *       заявок по правилам группы безопасности.</li>
 * </ul>
 *
 * @param userId      текущий пользователь
 * @param allTickets  показатели по всем заявкам арендатора, иначе только по {@code groupIds} и своим
 * @param groupIds    группы пользователя
 * @param drill       может открывать списки заявок
 * @param drillAll    список без ограничения группами
 */
record ReportScope(UUID userId, boolean allTickets, Set<UUID> groupIds, boolean drill, boolean drillAll) {

    /** Роли, которым открыт раздел отчётов. Совпадает с проверкой контроллера. */
    static final String READERS = "hasAnyRole('ANL', 'MIM', 'AUD', 'ADM', 'LEAD', 'AGT')";

    private static final Set<Role> TENANT_WIDE = EnumSet.of(Role.ANL, Role.MIM, Role.AUD, Role.ADM);
    private static final Set<Role> DRILL_ALL = EnumSet.of(Role.ANL, Role.MIM, Role.AUD);
    private static final Set<Role> GROUP_SCOPED = EnumSet.of(Role.AGT, Role.LEAD);

    ReportScope {
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** Чтение группы в фильтре: чужая группа для пользователя с областью «свои группы» — 404, как в заявках. */
    void requireGroup(UUID groupId) {
        if (groupId != null && !allTickets && !groupIds.contains(groupId)) {
            throw ApiException.notFound("Группа");
        }
    }

    void requireDrill() {
        if (!drill) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "drill_down_forbidden",
                    "Список заявок доступен ролям, которые читают заявки");
        }
    }

    @Component
    static class Factory {

        private final UserDirectory directory;

        Factory(UserDirectory directory) {
            this.directory = directory;
        }

        ReportScope current() {
            UUID userId = CurrentActor.userId().orElseThrow(ApiException::forbidden);
            Set<Role> roles = roles();
            boolean all = roles.stream().anyMatch(TENANT_WIDE::contains);
            boolean drillAll = roles.stream().anyMatch(DRILL_ALL::contains);
            boolean grouped = roles.stream().anyMatch(GROUP_SCOPED::contains);
            if (!all && !grouped) {
                throw ApiException.forbidden();
            }
            return new ReportScope(userId, all, directory.groupsOf(userId), drillAll || grouped, drillAll);
        }

        private static Set<Role> roles() {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                for (GrantedAuthority a : auth.getAuthorities()) {
                    String name = a.getAuthority();
                    if (name != null && name.startsWith("ROLE_")) {
                        Role.parse(name.substring(5)).ifPresent(roles::add);
                    }
                }
            }
            return roles;
        }
    }
}
