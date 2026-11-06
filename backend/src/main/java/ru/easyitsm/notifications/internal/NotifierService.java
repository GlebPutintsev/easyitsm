package ru.easyitsm.notifications.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.notifications.api.Notifier;

/** Уведомление от другого модуля (шаблон {@code message}). */
@Service
class NotifierService implements Notifier {

    private final UserDirectory users;
    private final Dispatcher dispatcher;

    NotifierService(UserDirectory users, Dispatcher dispatcher) {
        this.users = users;
        this.dispatcher = dispatcher;
    }

    @Override
    @Transactional
    public void notify(UUID userId, String kind, String title, String body, String link, boolean email) {
        users.findUser(userId).filter(u -> u.active()).ifPresent(u -> {
            Map<String, String> model = new HashMap<>();
            model.put("_kind", kind == null || kind.isBlank() ? "message" : kind);
            model.put("title", title == null ? "" : title);
            model.put("body", body == null ? "" : body);
            model.put("link", link == null ? "" : dispatcher.url(link));
            dispatcher.send(Dispatcher.Recipient.of(u),
                    new Dispatcher.Message("message", model, null, null, link), new Dispatcher.Channels(true, email, true));
        });
    }
}
