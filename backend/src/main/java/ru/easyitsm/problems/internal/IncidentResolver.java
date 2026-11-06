package ru.easyitsm.problems.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketLinks;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTransition;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Решает инциденты проблемы после исправления (PRB-04). Каждый инцидент — в своей транзакции: отказ цикла одной
 * заявки (подзадачи, проверка личности) не откатывает остальные. Переходы — от имени системы по ручным переходам
 * цикла; инцидент без исполнителя в статусе «Новая» или «Назначена» пропускается: его решает агент.
 */
@Component
class IncidentResolver {

    static final String RESOLUTION_CODE = "fixed";

    private final TicketQueries queries;
    private final TicketCommands commands;
    private final TicketLinks links;
    private final TransactionTemplate tx;

    IncidentResolver(TicketQueries queries, TicketCommands commands, TicketLinks links,
                     PlatformTransactionManager txManager) {
        this.queries = queries;
        this.commands = commands;
        this.links = links;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Открытые инциденты проблемы по связям {@code causes}. */
    List<TicketSnapshot> openIncidents(UUID problemId) {
        List<TicketSnapshot> out = new ArrayList<>();
        for (TicketLinks.TicketLinkRef l : links.links(problemId)) {
            if (!"causes".equals(l.kind())) {
                continue;
            }
            queries.find(l.otherId())
                    .filter(t -> t.type() == TicketType.INCIDENT && t.statusCategory().isActive())
                    .ifPresent(out::add);
        }
        return out;
    }

    ProblemDtos.ResolveResult resolveAll(TicketSnapshot problem, String comment) {
        List<String> resolved = new ArrayList<>();
        List<ProblemDtos.Skipped> skipped = new ArrayList<>();
        String text = "Причина устранена в рамках проблемы " + problem.number()
                + (comment == null || comment.isBlank() ? "" : ". " + comment.strip());
        for (TicketSnapshot incident : openIncidents(problem.id())) {
            String reason = resolveOne(incident, text);
            if (reason == null) {
                resolved.add(incident.number());
            } else {
                skipped.add(new ProblemDtos.Skipped(incident.number(), reason));
            }
        }
        return new ProblemDtos.ResolveResult(resolved, skipped);
    }

    /** @return null — решён, иначе код причины пропуска */
    private String resolveOne(TicketSnapshot incident, String text) {
        StatusCategory c = incident.statusCategory();
        if ((c == StatusCategory.NEW || c == StatusCategory.ASSIGNED) && incident.assigneeId() == null) {
            return "no_assignee";
        }
        try {
            tx.executeWithoutResult(s -> {
                if (c == StatusCategory.NEW || c == StatusCategory.ASSIGNED) {
                    commands.transition(incident.id(), new TicketTransition("in_progress", null, null, null, null),
                            "problem");
                }
                commands.transition(incident.id(),
                        new TicketTransition("resolved", null, null, RESOLUTION_CODE, text), "problem");
            });
            return null;
        } catch (ApiException e) {
            return e.code();
        }
    }
}
