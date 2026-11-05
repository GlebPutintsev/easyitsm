package ru.easyitsm.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;

/**
 * Временная реализация до слияния модуля tickets: каталог, формы и маршруты работают, а заказ
 * возвращает 503. Заменяется адаптером к {@code ru.easyitsm.tickets.api}.
 */
@Component
class PendingTicketGateway implements TicketGateway {

    @Override
    public TicketRef create(NewTicket ticket) {
        throw unavailable();
    }

    @Override
    public void applyApprovalOutcome(UUID ticketId, boolean approved, String reason) {
        throw unavailable();
    }

    @Override
    public Optional<TicketInfo> find(UUID ticketId) {
        return Optional.empty();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tickets_unavailable",
                "Создание заявок пока недоступно");
    }
}
