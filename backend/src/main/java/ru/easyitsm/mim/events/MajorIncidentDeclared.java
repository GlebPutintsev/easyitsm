package ru.easyitsm.mim.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Крупный инцидент объявлен (или согласовано объявление агента): командный центр открыт. */
public record MajorIncidentDeclared(UUID tenantId, Instant occurredAt, UUID mimId, UUID ticketId, String number,
                                    String publicTitle, List<UUID> serviceIds, UUID managerId, UUID actorId) {
}
