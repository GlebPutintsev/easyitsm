package ru.easyitsm.tickets.api;

import java.util.Set;
import java.util.UUID;

/**
 * Область чтения заявок текущего пользователя в виде данных, чтобы построить фильтр во внешнем индексе.
 * Заявку видно, если пользователь — её участник, или конфиденциальная заявка принадлежит его группе
 * безопасности, или неконфиденциальная подходит под одно из правил:
 * {@code allNonConfidential}; {@code groupScoped} и группа заявки среди {@code groupIds}; {@code unrouted} и
 * у заявки нет группы; организация заявки равна {@code organizationId}.
 *
 * @param seesInternal ищет и во внутренних заметках (поддержка и аудитор)
 */
public record TicketReadScope(
        UUID userId,
        Set<UUID> groupIds,
        boolean allNonConfidential,
        boolean groupScoped,
        boolean unrouted,
        UUID organizationId,
        boolean seesInternal) {

    public TicketReadScope {
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** То же правило для документа индекса: модуль поиска проверяет им свой фильтр. */
    public boolean canRead(TicketSearchDocument d) {
        if (userId != null && d.participantIds().contains(userId)) {
            return true;
        }
        if (d.confidential()) {
            return d.securityGroupId() != null && groupIds.contains(d.securityGroupId());
        }
        return allNonConfidential
                || (groupScoped && d.groupId() != null && groupIds.contains(d.groupId()))
                || (unrouted && d.groupId() == null)
                || (organizationId != null && organizationId.equals(d.organizationId()));
    }
}
