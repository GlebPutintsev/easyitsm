package ru.easyitsm.sla.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Таймеры SLA для карточки заявки, очереди и портала («Ожидаем решения до…»). */
public interface SlaQueries {

    /** Все счётчики заявки, включая завершённые и отменённые. */
    List<SlaInstanceView> forTicket(UUID ticketId);

    /** Действующие счётчики (running, paused) для строк очереди. Заявки без SLA в ответ не попадают. */
    Map<UUID, List<SlaInstanceView>> activeForTickets(Collection<UUID> ticketIds);
}
