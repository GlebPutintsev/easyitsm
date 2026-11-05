package ru.easyitsm.surveys.internal;

import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.surveys.events.SurveySubmitted;

@Service
class SurveyService {

    /** Роли, которые читают оценки любых заявок: поддержка, аналитика, аудит. */
    static final Set<String> READERS = Set.of("ROLE_AGT", "ROLE_LEAD", "ROLE_MIM", "ROLE_ANL", "ROLE_ADM", "ROLE_AUD");

    private final SurveyRepository surveys;
    private final TicketLookup tickets;
    private final ApplicationEventPublisher events;

    SurveyService(SurveyRepository surveys, TicketLookup tickets, ApplicationEventPublisher events) {
        this.surveys = surveys;
        this.tickets = tickets;
        this.events = events;
    }

    /** Оценка ставится один раз, только участником и только после закрытия (10.5). */
    @Transactional
    public SurveyResponse.SurveyView submit(SurveyCommands.Submit cmd) {
        UUID me = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        var ticket = tickets.find(cmd.ticketId()).filter(t -> t.participant(me))
                .orElseThrow(() -> ApiException.notFound("Заявка"));
        if (!"closed".equals(ticket.statusCategory())) {
            throw ApiException.conflict("ticket_not_closed", "Оценить можно после закрытия заявки");
        }
        if (surveys.findByTicketId(ticket.id()).isPresent()) {
            throw ApiException.conflict("already_rated", "Заявка уже оценена");
        }
        String comment = cmd.comment() == null || cmd.comment().isBlank() ? null : cmd.comment().strip();
        var saved = surveys.save(new SurveyResponse(ticket.id(), me, cmd.score(), comment));
        events.publishEvent(new SurveySubmitted(TenantContext.require(), ticket.id(), me, cmd.score(),
                comment != null));
        return saved.toView();
    }

    @Transactional(readOnly = true)
    public SurveyResponse.SurveyView get(UUID ticketId) {
        UUID me = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        var response = surveys.findByTicketId(ticketId).orElseThrow(() -> ApiException.notFound("Оценка"));
        boolean allowed = me.equals(response.respondentId()) || reader()
                || tickets.find(ticketId).map(t -> t.participant(me)).orElse(false);
        if (!allowed) {
            throw ApiException.notFound("Оценка");
        }
        return response.toView();
    }

    private static boolean reader() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(READERS::contains);
    }
}
