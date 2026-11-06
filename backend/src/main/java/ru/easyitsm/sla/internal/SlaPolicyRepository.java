package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface SlaPolicyRepository extends ListCrudRepository<SlaPolicy, UUID> {

    @Query("""
            select * from sla_policy
             where cast(:afterId as uuid) is null or id > cast(:afterId as uuid)
             order by id
             limit :limit""")
    List<SlaPolicy> page(UUID afterId, int limit);
}
