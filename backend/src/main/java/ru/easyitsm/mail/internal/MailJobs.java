package ru.easyitsm.mail.internal;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Плановые задачи канала почты в роли worker: отправка очереди и опрос ящиков. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class MailJobs {

    private static final Logger log = LoggerFactory.getLogger(MailJobs.class);

    private final MailDelivery delivery;
    private final MailPolling polling;

    MailJobs(MailDelivery delivery, MailPolling polling) {
        this.delivery = delivery;
        this.polling = polling;
    }

    @Scheduled(fixedDelayString = "${easyitsm.mail.delivery-interval:PT10S}", initialDelayString = "${easyitsm.mail.initial-delay:PT15S}")
    void deliver() {
        try {
            while (delivery.runOnce(Instant.now()) == MailDelivery.BATCH) {
                // следующая порция сразу
            }
        } catch (RuntimeException e) {
            log.error("Ошибка прохода очереди писем", e);
        }
    }

    @Scheduled(fixedDelayString = "${easyitsm.mail.poll-interval:PT60S}", initialDelayString = "${easyitsm.mail.initial-delay:PT30S}")
    void poll() {
        try {
            polling.runOnce(Instant.now());
        } catch (RuntimeException e) {
            log.error("Ошибка опроса почтовых ящиков", e);
        }
    }
}
