package ru.easyitsm.surveys.events;

import java.util.UUID;

/** Заявитель оценил закрытую заявку. Слушатели: отчёты, автоматизация (низкая оценка → разбор). */
public record SurveySubmitted(UUID tenantId, UUID ticketId, UUID respondentId, int score, boolean hasComment) {
}
