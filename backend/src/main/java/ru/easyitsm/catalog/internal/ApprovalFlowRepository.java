package ru.easyitsm.catalog.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface ApprovalFlowRepository extends ListCrudRepository<ApprovalFlow, UUID> {

    List<ApprovalFlow> findAllByOrderByNameAsc();
}
