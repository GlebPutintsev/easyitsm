package ru.easyitsm.integrations.internal;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.easyitsm.notifications.api.ExternalChannel;
import ru.easyitsm.notifications.api.ExternalNotice;

/**
 * Уведомления пользователю в привязанные боты (NTF-01). Что уходит наружу, решает настройка бота (CHN-06):
 * номер заявки всегда, название — {@code title}, текст — {@code text}, ссылка — {@code link}. Конфиденциальная
 * заявка приходит уже замаскированной. Сообщение ставится в очередь в транзакции рассылки.
 */
@Component
class MessengerNotices implements ExternalChannel {

    private final MessengerStore store;

    MessengerNotices(MessengerStore store) {
        this.store = store;
    }

    @Override
    public void deliver(ExternalNotice n) {
        var links = store.linksOf(n.userId());
        if (links.isEmpty()) {
            return;
        }
        Map<UUID, MessengerStore.Messenger> bots = store.list().stream()
                .collect(Collectors.toMap(MessengerStore.Messenger::id, Function.identity()));
        for (MessengerStore.Link l : links) {
            MessengerStore.Messenger m = bots.get(l.messengerId());
            if (m == null || !m.enabled()) {
                continue;
            }
            // Заявку из этого чата бот уже подтвердил своим сообщением.
            if ("ticket_created".equals(n.kind()) && n.ticketId() != null
                    && store.hasThread(m.id(), l.chatId(), n.ticketId())) {
                continue;
            }
            store.enqueue(m.id(), l.chatId(), render(m, n), null, n.ticketId(), n.ticketNumber());
        }
    }

    /** Текст сообщения по перечню передаваемых данных бота. */
    static String render(MessengerStore.Messenger m, ExternalNotice n) {
        boolean en = n.locale() != null && n.locale().startsWith("en");
        StringBuilder sb = new StringBuilder();
        if (m.shares("title")) {
            sb.append(n.subject());
        } else if (n.ticketNumber() != null) {
            sb.append(en ? "Ticket " : "Заявка ").append(n.ticketNumber()).append(en ? ": new notification" : ": новое уведомление");
        } else {
            sb.append(en ? "New notification" : "Новое уведомление");
        }
        if (m.shares("text") && n.text() != null && !n.text().isBlank()) {
            sb.append("\n\n").append(n.text());
        }
        if (m.shares("link") && n.url() != null) {
            sb.append("\n\n").append(n.url());
        }
        if (n.ticketId() != null) {
            sb.append("\n\n").append(MessengerBot.replyHint(m.kind(), n.locale()));
        }
        return sb.toString();
    }
}
