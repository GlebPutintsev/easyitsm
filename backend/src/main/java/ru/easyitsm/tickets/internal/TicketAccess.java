package ru.easyitsm.tickets.internal;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;

/**
 * Проверки доступа к заявкам на сервере (ACC-01). Невидимая заявка неотличима от отсутствующей: 404.
 * <p>
 * Участник (заявитель, получатель, исполнитель, наблюдатель) видит заявку всегда. Конфиденциальную, кроме
 * участников, — только члены её группы безопасности. Остальные — по области {@link AccessScopes.Scope}.
 */
@Component
class TicketAccess {

    private final AccessScopes scopes;
    private final JdbcClient jdbc;

    TicketAccess(AccessScopes scopes, JdbcClient jdbc) {
        this.scopes = scopes;
        this.jdbc = jdbc;
    }

    boolean canRead(TicketRow t, Actor actor) {
        if (actor.system()) {
            return true;
        }
        if (t.isParticipant(actor.userId()) || isWatcher(t.id, actor.userId())) {
            return true;
        }
        AccessScopes.Scope s = scopes.scopeOf(actor);
        if (t.confidential) {
            return t.securityGroupId != null && s.groupIds().contains(t.securityGroupId);
        }
        return s.allNonConfidential()
                || (s.groupScoped() && t.groupId != null && s.groupIds().contains(t.groupId))
                || (s.organizationId() != null && s.organizationId().equals(t.organizationId));
    }

    TicketRow requireRead(TicketRow t, Actor actor) {
        if (!canRead(t, actor)) {
            throw ApiException.notFound("Заявка");
        }
        return t;
    }

    /** Изменять поля, назначать, связывать: поддержка, которая видит заявку. Аудитор только читает. */
    void requireSupport(Actor actor) {
        if (!actor.isSupport()) {
            throw ApiException.forbidden();
        }
    }

    boolean isWatcher(UUID ticketId, UUID userId) {
        if (userId == null) {
            return false;
        }
        return jdbc.sql("select exists (select 1 from ticket_watcher where ticket_id = :t and user_id = :u)")
                .param("t", ticketId).param("u", userId).query(Boolean.class).single();
    }

    /** Условие WHERE для списков, алиас таблицы {@code t}. Параметры добавляются в {@code params}. */
    String filter(Actor actor, Map<String, Object> params) {
        if (actor.system()) {
            return "true";
        }
        AccessScopes.Scope s = scopes.scopeOf(actor);
        params.put("acMe", actor.userId());
        params.put("acGroups", Sql.array(s.groupIds()));
        params.put("acAll", s.allNonConfidential());
        params.put("acGroupScoped", s.groupScoped());
        params.put("acOrg", s.organizationId());
        return """
                (t.requester_id = :acMe or t.created_for_id = :acMe or t.assignee_id = :acMe
                 or exists (select 1 from ticket_watcher w where w.ticket_id = t.id and w.user_id = :acMe)
                 or (t.confidential and t.security_group_id = any(cast(:acGroups as uuid[])))
                 or (not t.confidential and (:acAll
                      or (:acGroupScoped and t.group_id = any(cast(:acGroups as uuid[])))
                      or (cast(:acOrg as uuid) is not null and t.organization_id = cast(:acOrg as uuid)))))""";
    }
}
