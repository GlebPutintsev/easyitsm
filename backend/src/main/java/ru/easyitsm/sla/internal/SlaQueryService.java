package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.sla.api.SlaInstanceView;
import ru.easyitsm.sla.api.SlaQueries;

@Service
class SlaQueryService implements SlaQueries {

    private final SlaInstanceRepository instances;
    private final SlaInstanceEventRepository journal;
    private final SlaPolicyRepository policies;
    private final Calendars calendars;

    SlaQueryService(SlaInstanceRepository instances, SlaInstanceEventRepository journal, SlaPolicyRepository policies,
                    Calendars calendars) {
        this.instances = instances;
        this.journal = journal;
        this.policies = policies;
        this.calendars = calendars;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlaInstanceView> forTicket(UUID ticketId) {
        return views(instances.findByTicketIdOrderById(ticketId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<SlaInstanceView>> activeForTickets(Collection<UUID> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<SlaInstanceView>> out = new LinkedHashMap<>();
        for (SlaInstanceView v : views(instances.findActive(ticketIds))) {
            out.computeIfAbsent(v.ticketId(), k -> new java.util.ArrayList<>()).add(v);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SlaViews.InstanceDetail detail(UUID id) {
        SlaInstance i = instances.findById(id).orElseThrow(() -> ApiException.notFound("Счётчик SLA"));
        SlaInstanceView view = views(List.of(i)).getFirst();
        return new SlaViews.InstanceDetail(view, i.pauses().stream().map(SlaPause::toView).toList(),
                journal.findByInstanceIdOrderById(id).stream().map(SlaInstanceEvent::toView).toList());
    }

    private List<SlaInstanceView> views(List<SlaInstance> list) {
        Instant now = Instant.now();
        Calendars.Session cals = calendars.session();
        Map<UUID, String> names = new HashMap<>();
        return list.stream().map(i -> i.toView(cals.of(i.scheduleId()), now,
                names.computeIfAbsent(i.policyId(), pid -> policies.findById(pid).map(SlaPolicy::name).orElse(""))))
                .toList();
    }
}
