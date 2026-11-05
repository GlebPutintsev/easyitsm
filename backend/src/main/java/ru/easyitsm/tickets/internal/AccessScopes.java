package ru.easyitsm.tickets.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Область видимости пользователя для ABAC (ACC-02, ACC-04). Совпадает по смыслу с {@code AccessScope}
 * модуля identity и будет брать его оттуда, когда identity появится в main.
 * <p>
 * До этого членство в группах неизвестно: роли поддержки, MIM и AUD видят все неконфиденциальные заявки,
 * остальные — только свои.
 */
interface AccessScopes {

    Scope scopeOf(Actor actor);

    /**
     * @param allNonConfidential видит все неконфиденциальные заявки арендатора
     * @param groupScoped        видит неконфиденциальные заявки групп {@code groupIds}
     * @param organizationId     видит неконфиденциальные заявки организации, null — политика выключена
     */
    record Scope(UUID userId, Set<UUID> groupIds, boolean allNonConfidential, boolean groupScoped,
                 UUID organizationId) {
    }
}

@Component
class RoleBasedAccessScopes implements AccessScopes {

    @Override
    public Scope scopeOf(Actor actor) {
        boolean all = actor.system() || actor.isSupport() || actor.has("AUD");
        return new Scope(actor.userId(), Set.of(), all, false, null);
    }
}
