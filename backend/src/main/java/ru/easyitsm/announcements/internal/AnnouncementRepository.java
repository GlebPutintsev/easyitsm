package ru.easyitsm.announcements.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface AnnouncementRepository extends ListCrudRepository<Announcement, UUID> {

    /**
     * Keyset-выборка по (starts_at desc, id desc). RLS сама ограничивает строки арендатором,
     * но фильтр по tenant_id всё равно не нужен: он лишь продублировал бы политику.
     */
    @Query("""
            select * from announcement
             where (:onlyActive = false
                    or (published and starts_at <= now() and (ends_at is null or ends_at > now())))
               and (cast(:afterStartsAt as timestamptz) is null
                    or (starts_at, id) < (cast(:afterStartsAt as timestamptz), cast(:afterId as uuid)))
             order by starts_at desc, id desc
             limit :limit""")
    List<Announcement> page(boolean onlyActive, Instant afterStartsAt, UUID afterId, int limit);
}
