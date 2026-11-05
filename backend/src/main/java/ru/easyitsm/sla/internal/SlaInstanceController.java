package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.sla.api.SlaInstanceView;

/**
 * Счётчики SLA заявки ({@code GET /sla/instances}, раздел 8.2 ТЗ) для поддержки, аналитиков и аудиторов.
 * Заявитель видит срок через API заявок и портала.
 * TODO(identity): проверка доступа к самой заявке (ABAC «своих групп»), когда tickets отдаст её в api.
 */
@RestController
@RequestMapping("/api/v1/sla/instances")
@PreAuthorize("hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'ANL', 'AUD', 'ADM')")
class SlaInstanceController {

    private final SlaQueryService service;

    SlaInstanceController(SlaQueryService service) {
        this.service = service;
    }

    @GetMapping
    List<SlaInstanceView> byTicket(@RequestParam UUID ticketId) {
        return service.forTicket(ticketId);
    }

    /** Счётчик с журналом пауз и историей пересчётов (SLA-04, SLA-06). */
    @GetMapping("/{id}")
    SlaViews.InstanceDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }
}
