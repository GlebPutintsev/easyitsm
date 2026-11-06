package ru.easyitsm.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Справочник пользователей и групп текущего арендатора. Чтение идёт под RLS, поэтому чужой арендатор
 * не виден, а для отсутствующего id возвращается пустой результат.
 */
public interface UserDirectory {

    Optional<UserSummary> findUser(UUID userId);

    List<UserSummary> findUsers(Collection<UUID> userIds);

    Optional<GroupSummary> findGroup(UUID groupId);

    /** Группы арендатора по имени. type — support, security, approval или other; null — все типы. */
    List<GroupSummary> listGroups(String type);

    /** Группы, в которых состоит пользователь. */
    Set<UUID> groupsOf(UUID userId);

    /** Активные (не заблокированные) участники группы, для распределения заявок внутри группы. */
    List<UUID> activeMembersOf(UUID groupId);

    /** Руководитель группы (участник с признаком lead). Если их несколько — первый добавленный. */
    Optional<UUID> leaderOf(UUID groupId);

    /** Организация пользователя, например для снимка заявки и SLA. */
    Optional<UUID> organizationOf(UUID userId);

    boolean isMember(UUID groupId, UUID userId);

    /** Действующие роли пользователя: выданные лично и через группы, с учётом срока. */
    Set<Role> rolesOf(UUID userId);
}
