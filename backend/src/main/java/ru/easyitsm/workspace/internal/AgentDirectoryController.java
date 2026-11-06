package ru.easyitsm.workspace.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.admin.api.ReasonCodes;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.TicketDirectory;

/**
 * Справочник рабочего места агента одним запросом: фронтенд кэширует его на сессию страницы и по нему
 * превращает id из ответов модуля заявок в имена. Форма ответа — {@code Directory} в
 * {@code frontend/src/features/agent/api/model.ts}.
 */
@RestController
class AgentDirectoryController {

    /** Те же роли, что видят очередь: все сотрудники поддержки, без заявителя и согласующего. */
    static final String STAFF = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST', 'ANL', 'ADM', 'SEC', 'AUD')";

    /** Группы, на которые назначают заявки. Группы безопасности и согласования в очереди не участвуют. */
    static final String ASSIGNMENT_GROUPS = "support";

    record Ref(String id, String name) {
    }

    record UserRef(UUID id, String name, String email) {
    }

    record Agent(UUID id, String name, String email, List<UUID> groupIds, long openTickets, boolean available) {
    }

    record Directory(UserRef me, List<Ref> groups, List<Agent> agents, List<Ref> services, List<Ref> categories,
                     List<Ref> resolutionCodes, List<Ref> pendingReasons, List<Ref> cancelReasons) {
    }

    private final UserDirectory users;
    private final CatalogQueries catalog;
    private final TicketDirectory tickets;
    private final ReasonCodes reasons;

    AgentDirectoryController(UserDirectory users, CatalogQueries catalog, TicketDirectory tickets,
                             ReasonCodes reasons) {
        this.users = users;
        this.catalog = catalog;
        this.tickets = tickets;
        this.reasons = reasons;
    }

    /** Коды решения, приостановки и отмены настраиваются в «Управлении → Справочники» (модуль admin). */
    private List<Ref> reasons(ReasonCodes.Kind kind) {
        return reasons.active(kind).stream().map(i -> new Ref(i.code(), i.name())).toList();
    }

    @GetMapping("/api/v1/agent-workspace/directory")
    @PreAuthorize(STAFF)
    @Transactional(readOnly = true)
    Directory directory() {
        UUID meId = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        UserSummary me = users.findUser(meId).orElseThrow(ApiException::forbidden);

        List<GroupSummary> groups = users.listGroups(ASSIGNMENT_GROUPS);
        // Агент — активный участник хотя бы одной группы назначения; порядок групп сохраняем по имени группы.
        Map<UUID, Set<UUID>> groupsByAgent = new LinkedHashMap<>();
        for (GroupSummary g : groups) {
            for (UUID member : users.activeMembersOf(g.id())) {
                groupsByAgent.computeIfAbsent(member, k -> new LinkedHashSet<>()).add(g.id());
            }
        }
        Map<UUID, Long> load = tickets.openCountByAssignee(groupsByAgent.keySet());
        List<Agent> agents = new ArrayList<>();
        for (UserSummary u : users.findUsers(groupsByAgent.keySet())) {
            agents.add(new Agent(u.id(), u.displayName(), u.email(), List.copyOf(groupsByAgent.get(u.id())),
                    load.getOrDefault(u.id(), 0L), u.active()));
        }
        agents.sort(Comparator.comparing((Agent a) -> a.name().toLowerCase(java.util.Locale.ROOT)).thenComparing(Agent::id));

        return new Directory(
                new UserRef(me.id(), me.displayName(), me.email()),
                groups.stream().map(g -> new Ref(g.id().toString(), g.name())).toList(),
                agents,
                catalog.activeItems().stream().map(i -> new Ref(i.id().toString(), i.name())).toList(),
                tickets.categories().stream().filter(TicketDirectory.CategoryRef::active)
                        .map(c -> new Ref(c.id().toString(), c.name())).toList(),
                reasons(ReasonCodes.Kind.RESOLUTION),
                reasons(ReasonCodes.Kind.PENDING),
                reasons(ReasonCodes.Kind.CANCEL));
    }
}
