package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface SlaInstanceEventRepository extends ListCrudRepository<SlaInstanceEvent, UUID> {

    List<SlaInstanceEvent> findByInstanceIdOrderById(UUID instanceId);
}
