package ru.easyitsm.tickets.internal;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.web.ApiException;

/**
 * Проверки доступа к заявкам на сервере (ACC-01, ACC-02, ACC-04). Невидимая заявка неотличима от
 * отсутствующей: 404. Видимая, но чужая для работы — 403.
 * <p>
 * <b>Чтение.</b> Участник (заявитель, получатель, исполнитель, наблюдатель, кто зарегистрировал) видит заявку
 * всегда. Конфиденциальную, кроме участников, — только члены её группы безопасности. Остальные — по области
 * {@link AccessScopes.Scope}: MIM и AUD — все, AGT, LEAD, CHG, PRB, CFG, AST, KNW — заявки своих групп
 * и нераспределённые (без группы).
 * <p>
 * <b>Работа</b> (поля, назначение, переходы по ролям, эскалация, связи, слияние): поддержка, которая видит
 * заявку и при этом исполнитель, член назначенной группы, MIM, а у конфиденциальной — член группы
 * безопасности. Нераспределённую заявку может взять любой из поддержки, кто её видит. Агент чужой группы,
 * подписанный наблюдателем, читает и комментирует, но не меняет заявку (матрица 3.2, «Назначить — О»).
 */
@Component
class TicketAccess {

    private final AccessScopes scopes;
    private final UserDirectory directory;
    private final JdbcClient jdbc;

    TicketAccess(AccessScopes scopes, UserDirectory directory, JdbcClient jdbc) {
        this.scopes = scopes;
        this.directory = directory;
        this.jdbc = jdbc;
    }

    AccessScopes.Scope scope(Actor actor) {
        return scopes.scopeOf(actor);
    }

    boolean canRead(TicketRow t, Actor actor) {
        if (actor.system()) {
            return true;
        }
        if (isParticipant(t, actor) || isWatcher(t.id, actor.userId())) {
            return true;
        }
        AccessScopes.Scope s = scopes.scopeOf(actor);
        if (t.confidential) {
            return s.inGroup(t.securityGroupId);
        }
        return s.allNonConfidential()
                || (s.groupScoped() && s.inGroup(t.groupId))
                || (s.unrouted() && t.groupId == null)
                || (s.organizationId() != null && s.organizationId().equals(t.organizationId));
    }

    /** Может ли поддержка менять заявку. Чтение проверяется отдельно ({@link #requireWork}). */
    boolean canWork(TicketRow t, Actor actor) {
        if (actor.system()) {
            return true;
        }
        if (!actor.isSupport()) {
            return false;
        }
        if (actor.is(t.assigneeId)) {
            return true;
        }
        AccessScopes.Scope s = scopes.scopeOf(actor);
        if (t.confidential) {
            return s.inGroup(t.securityGroupId);
        }
        return s.allNonConfidential() || t.groupId == null || s.inGroup(t.groupId);
    }

    TicketRow requireRead(TicketRow t, Actor actor) {
        if (!canRead(t, actor)) {
            throw ApiException.notFound("Заявка");
        }
        return t;
    }

    /** Поддержка, которая видит заявку и работает с ней: иначе 404 (не видна) или 403 (чужая группа). */
    TicketRow requireWork(TicketRow t, Actor actor) {
        requireSupport(actor);
        requireRead(t, actor);
        if (!canWork(t, actor)) {
            throw ApiException.forbidden();
        }
        return t;
    }

    /** Изменять поля, назначать, связывать: поддержка, которая видит заявку. Аудитор только читает. */
    void requireSupport(Actor actor) {
        if (!actor.isSupport()) {
            throw ApiException.forbidden();
        }
    }

    /**
     * Назначение, выбранное пользователем: группа есть в справочнике, исполнитель состоит в группе заявки.
     * Маршрутизация и вызовы других модулей (система) не проверяются: их настраивает администратор.
     */
    void requireValidAssignment(UUID groupId, UUID assigneeId, Actor actor) {
        if (actor.system() || groupId == null) {
            return;
        }
        if (directory.findGroup(groupId).isEmpty()) {
            throw TicketProblem.invalidAssignment("groupId", "Группа не найдена");
        }
        if (assigneeId != null && !directory.isMember(groupId, assigneeId)) {
            throw TicketProblem.invalidAssignment("assigneeId", "Исполнитель не состоит в группе заявки");
        }
    }

    /** То же для изменения: проверяется, только если группа или исполнитель поменялись. */
    void requireValidAssignment(TicketRow before, TicketRow after, Actor actor) {
        if (!Objects.equals(before.groupId, after.groupId) || !Objects.equals(before.assigneeId, after.assigneeId)) {
            requireValidAssignment(after.groupId, after.assigneeId, actor);
        }
    }

    static boolean isParticipant(TicketRow t, Actor actor) {
        UUID me = actor.userId();
        return t.isParticipant(me) || (me != null && me.equals(t.createdBy));
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
        params.put("acUnrouted", s.unrouted());
        params.put("acOrg", s.organizationId());
        return """
                (t.requester_id = :acMe or t.created_for_id = :acMe or t.assignee_id = :acMe or t.created_by = :acMe
                 or exists (select 1 from ticket_watcher w where w.ticket_id = t.id and w.user_id = :acMe)
                 or (t.confidential and t.security_group_id = any(cast(:acGroups as uuid[])))
                 or (not t.confidential and (:acAll
                      or (:acGroupScoped and t.group_id = any(cast(:acGroups as uuid[])))
                      or (:acUnrouted and t.group_id is null)
                      or (cast(:acOrg as uuid) is not null and t.organization_id = cast(:acOrg as uuid)))))""";
    }

    /** Группы пользователя для фильтра {@code groupId=mine} и «взять следующую». */
    Set<UUID> myGroups(Actor actor) {
        return actor.system() ? Set.of() : scopes.scopeOf(actor).groupIds();
    }
}
