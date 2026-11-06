package ru.easyitsm.admin.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface DutyShiftRepository extends ListCrudRepository<DutyShift, UUID> {

    /** Смены группы, пересекающие интервал [from, to). */
    @Query("""
            select * from duty_shift
             where group_id = :groupId and starts_at < :to and ends_at > :from
             order by starts_at, id""")
    List<DutyShift> overlapping(UUID groupId, Instant from, Instant to);
}
