package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.sla.api.SlaInstanceView;
import ru.easyitsm.tickets.api.TicketQueries;

/**
 * Счётчики SLA заявки ({@code GET /sla/instances}, раздел 8.2 ТЗ) для поддержки, аналитиков и аудиторов.
 * Заявитель видит срок через API заявок и портала.
 * <p>
 * Счётчик виден тому, кто видит саму заявку по правилам модуля заявок ({@link TicketQueries#findVisible}):
 * агент чужой группы или конфиденциальной заявки получает 404, как и на саму заявку (ACC-01, AT-01).
 */
@RestController
@RequestMapping("/api/v1/sla/instances")
@PreAuthorize("hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'ANL', 'AUD', 'ADM')")
class SlaInstanceController {

    private final SlaQueryService service;
    private final TicketQueries tickets;

    SlaInstanceController(SlaQueryService service, TicketQueries tickets) {
        this.service = service;
        this.tickets = tickets;
    }

    @GetMapping
    List<SlaInstanceView> byTicket(@RequestParam UUID ticketId) {
        requireVisible(ticketId);
        return service.forTicket(ticketId);
    }

    /** Счётчик с журналом пауз и историей пересчётов (SLA-04, SLA-06). */
    @GetMapping("/{id}")
    SlaViews.InstanceDetail detail(@PathVariable UUID id) {
        SlaViews.InstanceDetail detail = service.detail(id);
        if (tickets.findVisible(detail.instance().ticketId()).isEmpty()) {
            throw ApiException.notFound("Счётчик SLA");
        }
        return detail;
    }

    private void requireVisible(UUID ticketId) {
        if (tickets.findVisible(ticketId).isEmpty()) {
            throw ApiException.notFound("Заявка");
        }
    }
}
