package ru.easyitsm.surveys.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

final class SurveyCommands {

    private SurveyCommands() {
    }

    record Submit(@NotNull UUID ticketId, @Min(1) @Max(5) int score, @Size(max = 2_000) String comment) {
    }
}
