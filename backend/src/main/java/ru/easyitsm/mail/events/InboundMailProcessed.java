package ru.easyitsm.mail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Входящее письмо обработано (поток 6.4). Слушатели: аудит, отчёты по каналам.
 *
 * @param verdict ticket_created, comment_added, auto_reply, loop, rate_limited, auth_failed, quarantined,
 *                unknown_sender, released или error
 * @param ticketId заявка, созданная или дополненная письмом, иначе null
 */
public record InboundMailProcessed(UUID tenantId, UUID inboundId, UUID mailboxId, String fromAddress,
                                   String verdict, UUID ticketId, Instant receivedAt) {
}
