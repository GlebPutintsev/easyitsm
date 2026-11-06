package ru.easyitsm.integrations.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Опрос ботов и отправка очереди сообщений, в арендаторе вызывающего. Опрос бота держит рекомендательную
 * блокировку транзакции: два пода не возьмут одни и те же сообщения. Каждое сообщение пользователя
 * обрабатывается в своей транзакции, ошибка одного не мешает остальным. Позиция опроса сохраняется после пачки.
 */
@Service
class MessengerRunner {

    private static final Logger log = LoggerFactory.getLogger(MessengerRunner.class);
    static final int MAX_ATTEMPTS = 5;

    private final MessengerStore store;
    private final MessengerService service;
    private final MessengerBot bot;
    private final TransactionTemplate tx;

    MessengerRunner(MessengerStore store, MessengerService service, MessengerBot bot, PlatformTransactionManager txManager) {
        this.store = store;
        this.service = service;
        this.bot = bot;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return сколько сообщений обработано; -1 — бот опрашивает другой под или он выключен */
    int poll(UUID messengerId) {
        Integer n = tx.execute(s -> {
            if (!store.tryLockPoll(messengerId)) {
                return -1;
            }
            MessengerStore.Messenger m = store.find(messengerId).filter(MessengerStore.Messenger::enabled).orElse(null);
            if (m == null) {
                return -1;
            }
            MessengerConnector.Api api = service.api(m);
            if (api == null) {
                store.polled(m.id(), null, "токен не расшифровывается: укажите его заново", false);
                return 0;
            }
            MessengerConnector.Poll poll;
            try {
                poll = service.connector(m.kind()).poll(api, store.cursor(m.id()).orElse(null));
            } catch (MessengerConnector.Failure e) {
                store.polled(m.id(), null, e.getMessage(), false);
                return 0;
            }
            for (MessengerConnector.Update u : poll.updates()) {
                try {
                    tx.execute(inner -> bot.handle(m, u));
                } catch (RuntimeException e) {
                    log.warn("Бот {}: сообщение не обработано", m.id(), e);
                }
            }
            store.polled(m.id(), poll.cursor(), null, !poll.updates().isEmpty());
            return poll.updates().size();
        });
        return n == null ? 0 : n;
    }

    /** Отправка одного сообщения очереди. @return true — отправлялось (успешно или нет) */
    boolean send(UUID outboxId) {
        Boolean sent = tx.execute(s -> {
            MessengerStore.Outgoing o = store.lockOutgoing(outboxId).orElse(null);
            if (o == null) {
                return false;
            }
            int attempt = o.attempts() + 1;
            MessengerStore.Messenger m = store.find(o.messengerId()).orElse(null);
            if (m == null || !m.enabled()) {
                store.failed(o.id(), attempt, "бот выключен", null);
                return true;
            }
            MessengerConnector.Api api = service.api(m);
            if (api == null) {
                store.failed(o.id(), attempt, "токен не расшифровывается", null);
                return true;
            }
            try {
                String id = service.connector(m.kind()).send(api, o.chatId(), o.text(), o.replyTo());
                store.sent(o.id(), attempt);
                store.thread(m.id(), o.chatId(), id, o.ticketId());
            } catch (MessengerConnector.Failure e) {
                Instant next = attempt >= MAX_ATTEMPTS ? null : Instant.now().plus(backoff(attempt));
                store.failed(o.id(), attempt, e.getMessage(), next);
                if (next == null) {
                    log.warn("Бот {}: сообщение {} не доставлено после {} попыток: {}", m.id(), o.id(), attempt, e.getMessage());
                }
            }
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }

    static Duration backoff(int attempt) {
        return Duration.ofMinutes(1L << Math.min(Math.max(attempt - 1, 0), 6));
    }
}
