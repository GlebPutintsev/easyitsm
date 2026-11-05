package ru.easyitsm.catalog.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface ApprovalRepository extends ListCrudRepository<Approval, UUID> {

    List<Approval> findByRequestIdOrderByStepIndexAscCreatedAtAsc(UUID requestId);

    List<Approval> findByRequestIdAndStepIndex(UUID requestId, int stepIndex);

    @Query("""
            select count(*) from approval a join approval_request r on r.id = a.request_id
             where a.approver_id = :approverId and a.decision = 'pending' and r.state = 'pending'""")
    long pendingCount(UUID approverId);
}
