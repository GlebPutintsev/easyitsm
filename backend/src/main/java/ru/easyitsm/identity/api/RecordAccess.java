package ru.easyitsm.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Атрибуты записи, по которым решается доступ (ACC-02): организация, назначенная группа, заявитель,
 * участники и конфиденциальность. Арендатора проверяет RLS, поэтому его здесь нет.
 *
 * @param requesterId     заявитель
 * @param assigneeId      исполнитель
 * @param assignedGroupId назначенная группа
 * @param organizationId  организация заявителя
 * @param participantIds  наблюдатели и другие участники
 * @param confidential    признак «Конфиденциальная» (ACC-04)
 * @param securityGroupId группа безопасности для конфиденциальной записи
 */
public record RecordAccess(
        UUID requesterId,
        UUID assigneeId,
        UUID assignedGroupId,
        UUID organizationId,
        Set<UUID> participantIds,
        boolean confidential,
        UUID securityGroupId) {

    public RecordAccess {
        participantIds = participantIds == null ? Set.of() : Set.copyOf(participantIds);
    }

    public boolean isParticipant(UUID userId) {
        return userId != null && (userId.equals(requesterId) || userId.equals(assigneeId)
                || participantIds.contains(userId));
    }
}
