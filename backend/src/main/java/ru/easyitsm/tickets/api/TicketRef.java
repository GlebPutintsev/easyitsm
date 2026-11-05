package ru.easyitsm.tickets.api;

import java.util.UUID;

/** Ссылка на созданную заявку. */
public record TicketRef(UUID id, String number, String statusCode, StatusCategory statusCategory) {
}
