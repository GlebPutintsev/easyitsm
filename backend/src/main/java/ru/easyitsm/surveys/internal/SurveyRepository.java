package ru.easyitsm.surveys.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface SurveyRepository extends ListCrudRepository<SurveyResponse, UUID> {

    Optional<SurveyResponse> findByTicketId(UUID ticketId);
}
