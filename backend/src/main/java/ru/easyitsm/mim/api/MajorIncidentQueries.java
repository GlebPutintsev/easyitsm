package ru.easyitsm.mim.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Текущие крупные инциденты для других модулей: баннер сбоя над статьёй базы знаний, портал, мессенджеры.
 * В арендаторе вызывающего, без проверки прав: наружу только публичное название и услуги.
 */
public interface MajorIncidentQueries {

    /** Действующие (объявленные и ещё не закрытые) крупные инциденты, новые первыми. */
    List<ActiveMajorIncident> active();

    /** Действующий крупный инцидент заявки. */
    Optional<ActiveMajorIncident> forTicket(UUID ticketId);

    /**
     * @param state     {@code active} или {@code restored} (сервис восстановлен, разбор ещё идёт)
     * @param serviceIds затронутые услуги каталога
     */
    record ActiveMajorIncident(UUID id, UUID ticketId, String number, String publicTitle, String state,
                               List<UUID> serviceIds, Instant declaredAt, Instant restoredAt) {
    }
}
