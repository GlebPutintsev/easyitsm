package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface SlaInstanceRepository extends ListCrudRepository<SlaInstance, UUID> {

    List<SlaInstance> findByTicketIdOrderById(UUID ticketId);

    @Query("select * from sla_instance where ticket_id in (:ticketIds) and state in ('running', 'paused') order by id")
    List<SlaInstance> findActive(Collection<UUID> ticketIds);

    /** Изменились график или праздники: работающие счётчики пересчитает фоновый обработчик (SLA-06). */
    @Modifying
    @Query("""
            update sla_instance set next_check_at = :now
             where schedule_id = :scheduleId and state in ('running', 'paused')""")
    int requestRecalculation(UUID scheduleId, Instant now);
}
