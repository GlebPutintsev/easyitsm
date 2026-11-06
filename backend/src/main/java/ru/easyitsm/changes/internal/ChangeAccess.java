package ru.easyitsm.changes.internal;

import java.util.List;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.tickets.api.TicketQueries;

/**
 * Права на изменение (раздел 3 ТЗ, ACC-01, ACC-04, ACC-05). Роль на действие проверяет {@code @PreAuthorize}
 * контроллера, здесь — доступ к конкретной записи.
 * <ul>
 *   <li>Читать: поддержка (AGT, LEAD, MIM, CHG, PRB, CFG, AST) и аудитор — все неконфиденциальные (расписание
 *   изменений); автор, исполнитель, участник группы, согласующий и участник CAB с изменением в повестке — своё;
 *   остальные — если видят заявку по правилам модуля tickets. Конфиденциальное — только по правилам tickets,
 *   автор и исполнитель.</li>
 *   <li>Вести (поля, план, внедрение): менеджер изменений, автор, исполнитель и участники группы изменения.</li>
 * </ul>
 * Невидимое изменение — 404.
 */
@Component
class ChangeAccess {

    private final TicketQueries tickets;
    private final UserDirectory directory;
    private final CabStore cab;

    ChangeAccess(TicketQueries tickets, UserDirectory directory, CabStore cab) {
        this.tickets = tickets;
        this.directory = directory;
        this.cab = cab;
    }

    boolean canRead(ChangeRow c, Me me, List<ChangeStore.Approval> approvals) {
        if (me.is(c.authorId) || me.is(c.assigneeId)) {
            return true;
        }
        if (!c.confidential) {
            // Расписание изменений открыто всей поддержке: агенту нужно знать о работах на своих КЕ.
            if (me.support() || me.has("AUD")) {
                return true;
            }
            if (c.groupId != null && directory.isMember(c.groupId, me.userId())) {
                return true;
            }
            if (approvals.stream().anyMatch(a -> isApprover(a, me))) {
                return true;
            }
            if (cab.isMemberForChange(c.id, me.userId())) {
                return true;
            }
        }
        return tickets.findVisible(c.id).isPresent();
    }

    /** Ведёт изменение: поля, план, внедрение, шаги. Аудитор только читает. */
    boolean canWork(ChangeRow c, Me me) {
        if (me.roles().equals(java.util.Set.of("AUD"))) {
            return false;
        }
        return me.manager() || me.is(c.authorId) || me.is(c.assigneeId)
                || (c.groupId != null && me.support() && directory.isMember(c.groupId, me.userId()));
    }

    /** Слот, который пользователь может решить по своей роли или группе (без учёта автора — это ACC-05). */
    boolean isApprover(ChangeStore.Approval a, Me me) {
        return switch (a.kind()) {
            case "manager" -> me.manager();
            case "group" -> a.groupId() != null && directory.isMember(a.groupId(), me.userId());
            case "user" -> me.is(a.approverId());
            default -> false;
        };
    }
}
