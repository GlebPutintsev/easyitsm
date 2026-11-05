package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface WorkScheduleRepository extends ListCrudRepository<WorkSchedule, UUID> {

    @Query("""
            select * from work_schedule
             where cast(:afterId as uuid) is null or id > cast(:afterId as uuid)
             order by id
             limit :limit""")
    List<WorkSchedule> page(UUID afterId, int limit);
}
