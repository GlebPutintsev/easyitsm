package ru.easyitsm.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Область видимости текущего пользователя для построения фильтра списков в SQL. Правило то же, что в
 * {@link AccessDecisions#canRead(RecordAccess)}:
 * <ol>
 *   <li>участник записи (заявитель, исполнитель, наблюдатель) видит её всегда;</li>
 *   <li>конфиденциальную запись видят ещё только члены её группы безопасности;</li>
 *   <li>остальные записи: при {@code allNonConfidential} — все, иначе записи групп из {@code groupIds},
 *       если {@code groupScoped}, и записи организации {@code organizationId}, если она задана.</li>
 * </ol>
 *
 * @param userId             текущий пользователь
 * @param groupIds           группы пользователя (для правила группы безопасности и рабочих групп)
 * @param allNonConfidential роли с правом читать все неконфиденциальные записи (MIM, AUD)
 * @param groupScoped        роли, которые читают записи своих групп (AGT, LEAD и менеджеры процессов)
 * @param organizationId     организация, записи которой видны заявителю, если это разрешено политикой; иначе null
 */
public record AccessScope(
        UUID userId,
        Set<UUID> groupIds,
        boolean allNonConfidential,
        boolean groupScoped,
        UUID organizationId) {
}
