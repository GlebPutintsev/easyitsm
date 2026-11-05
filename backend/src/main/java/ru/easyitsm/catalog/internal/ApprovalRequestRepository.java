package ru.easyitsm.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface ApprovalRequestRepository extends ListCrudRepository<ApprovalRequest, UUID> {

    Optional<ApprovalRequest> findByTicketId(UUID ticketId);
}
