package ru.easyitsm.notifications.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.mail.api.MailOutbox;
import ru.easyitsm.mail.api.OutgoingMail;
import ru.easyitsm.notifications.api.ExternalChannel;
import ru.easyitsm.notifications.api.ExternalNotice;

/**
 * Доставка одного уведомления одному получателю по каналам: центр уведомлений, письмо, Web Push и внешние
 * каналы ({@link ExternalChannel}, мессенджеры) — вместе с центром уведомлений.
 * Шаблон выбирается по языку получателя.
 */
@Service
class Dispatcher {

    private final TemplateStore templates;
    private final NotificationStore center;
    private final MailOutbox mail;
    private final NotificationProperties props;
    private final JdbcClient jdbc;
    private final List<ExternalChannel> external;

    Dispatcher(TemplateStore templates, NotificationStore center, MailOutbox mail, NotificationProperties props,
               JdbcClient jdbc, List<ExternalChannel> external) {
        this.external = external;
        this.templates = templates;
        this.center = center;
        this.mail = mail;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Каналы доставки. {@code inApp} без пользователя невозможен (письмо на адрес из события сброса пароля). */
    record Channels(boolean inApp, boolean email, boolean push) {
    }

    /** Получатель: пользователь или только адрес. */
    record Recipient(UUID userId, String email, String name, String locale, String timezone) {

        static Recipient of(UserSummary u) {
            return new Recipient(u.id(), u.email(), u.displayName(), Templates.locale(u.locale()), u.timezone());
        }
    }

    /** Что за уведомление: вид, модель шаблона (без данных получателя), заявка и ссылка в интерфейсе. */
    record Message(String kind, Map<String, String> model, UUID ticketId, String ticketNumber, String path) {
    }

    void send(Recipient r, Message m, Channels channels) {
        Map<String, String> model = new HashMap<>(m.model());
        model.put("product", props.productName());
        model.putIfAbsent("recipient.name", r.name() == null ? "" : r.name());
        Templates.Template t = templates.effective(m.kind(), r.locale());
        String subject = TemplateRenderer.subject(t.subject(), model);
        String text = TemplateRenderer.render(t.body(), model);
        if (channels.inApp() && r.userId() != null) {
            boolean push = channels.push() && props.pushEnabled() && hasSubscription(r.userId());
            center.add(r.userId(), m.model().getOrDefault("_kind", m.kind()), subject, summary(text), m.path(), m.ticketId(), push);
            if (!external.isEmpty()) {
                String title = model.get("ticket.title");
                ExternalNotice notice = new ExternalNotice(r.userId(), r.locale(), m.model().getOrDefault("_kind", m.kind()),
                        subject, summary(text), m.ticketId(), m.ticketNumber(), title == null || title.isBlank() ? null : title,
                        m.path() == null ? null : props.url(m.path()), "yes".equals(model.get("confidential")));
                external.forEach(c -> c.deliver(notice));
            }
        }
        if (channels.email() && r.email() != null && !r.email().isBlank()) {
            mail.enqueue(new OutgoingMail(m.kind(), r.email(), r.name(), r.userId(), m.ticketId(), m.ticketNumber(),
                    subject, text, TemplateRenderer.html(text, props.productName())));
        }
    }

    String url(String path) {
        return props.url(path);
    }

    private boolean hasSubscription(UUID userId) {
        return jdbc.sql("select exists (select 1 from push_subscription where user_id = :u)").param("u", userId)
                .query(Boolean.class).single();
    }

    /** Краткий текст для центра уведомлений: первый абзац без ссылок. */
    static String summary(String text) {
        for (String para : text.split("\n\\s*\n")) {
            String p = para.strip();
            if (!p.isEmpty() && !p.startsWith("http") && !p.startsWith("Здравствуйте") && !p.startsWith("Hello")) {
                p = p.replaceAll("\\s+", " ");
                return p.length() > 300 ? p.substring(0, 299) + "…" : p;
            }
        }
        return "";
    }
}
