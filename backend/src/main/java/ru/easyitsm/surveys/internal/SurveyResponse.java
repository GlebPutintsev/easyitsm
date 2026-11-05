package ru.easyitsm.surveys.internal;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

@Table("survey_response")
class SurveyResponse extends TenantEntity {

    private UUID ticketId;
    private UUID respondentId;
    private short score;
    private String comment;

    protected SurveyResponse() {
    }

    SurveyResponse(UUID ticketId, UUID respondentId, int score, String comment) {
        this.ticketId = ticketId;
        this.respondentId = respondentId;
        this.score = (short) score;
        this.comment = comment;
    }

    UUID respondentId() {
        return respondentId;
    }

    SurveyView toView() {
        return new SurveyView(ticketId, respondentId, score, comment, getCreatedAt());
    }

    record SurveyView(UUID ticketId, UUID respondentId, int score, String comment, java.time.Instant createdAt) {
    }
}
