package ru.easyitsm.tickets.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import ru.easyitsm.identity.api.AccessDecisions;
import ru.easyitsm.identity.api.AccessScope;
import ru.easyitsm.identity.api.UserDirectory;

/**
 * Область видимости пользователя для ABAC (ACC-02, ACC-04). Берётся из {@link AccessDecisions#currentScope()}
 * модуля identity: группы пользователя, роли «видит всё» (MIM, AUD) и «видит свои группы» (AGT, LEAD, CHG,
 * PRB, CFG, AST, KNW), организация, если политика арендатора это разрешает.
 * <p>
 * Своё правило tickets: все, кто видит заявки своих групп, видят ещё нераспределённые (без группы), чтобы
 * заявку взял любой агент, если маршрутизация не нашла группу, а группа по умолчанию не задана.
 */
interface AccessScopes {

    Scope scopeOf(Actor actor);

    /**
     * @param allNonConfidential видит все неконфиденциальные заявки арендатора
     * @param groupScoped        видит неконфиденциальные заявки групп {@code groupIds}
     * @param organizationId     видит неконфиденциальные заявки организации, null — политика выключена
     * @param unrouted           видит неконфиденциальные заявки без группы
     */
    record Scope(UUID userId, Set<UUID> groupIds, boolean allNonConfidential, boolean groupScoped,
                 UUID organizationId, boolean unrouted) {

        public Scope {
            groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
        }

        boolean inGroup(UUID groupId) {
            return groupId != null && groupIds.contains(groupId);
        }
    }
}

@Component
class IdentityAccessScopes implements AccessScopes {

    private static final String CACHE = IdentityAccessScopes.class.getName() + ".scope";

    private final AccessDecisions decisions;
    private final UserDirectory directory;

    IdentityAccessScopes(AccessDecisions decisions, UserDirectory directory) {
        this.decisions = decisions;
        this.directory = directory;
    }

    /** Одна область на HTTP-запрос: списки связей и подзадач проверяют много заявок подряд. */
    @Override
    public Scope scopeOf(Actor actor) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST) instanceof Scope s
                && s.userId() != null && s.userId().equals(actor.userId())) {
            return s;
        }
        Scope scope = compute(actor);
        if (request != null) {
            request.setAttribute(CACHE, scope, RequestAttributes.SCOPE_REQUEST);
        }
        return scope;
    }

    private Scope compute(Actor actor) {
        if (actor.userId() == null) {
            return new Scope(null, Set.of(), false, false, null, false);
        }
        AccessScope s = decisions.currentScope();
        if (!actor.userId().equals(s.userId())) {
            // Действие не от имени вошедшего пользователя: только участие и группы, без ролевых прав.
            return new Scope(actor.userId(), directory.groupsOf(actor.userId()), false, false, null, false);
        }
        return new Scope(s.userId(), s.groupIds(), s.allNonConfidential(), s.groupScoped(), s.organizationId(),
                s.groupScoped());
    }
}
