package ru.easyitsm.sla.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface HolidayRepository extends ListCrudRepository<Holiday, UUID> {

    List<Holiday> findByScheduleIdOrderByDay(UUID scheduleId);

    @Query("select * from holiday where schedule_id = :scheduleId and day between :from and :to order by day")
    List<Holiday> findInRange(UUID scheduleId, LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from holiday where schedule_id = :scheduleId and day between :from and :to")
    int deleteInRange(UUID scheduleId, LocalDate from, LocalDate to);
}
