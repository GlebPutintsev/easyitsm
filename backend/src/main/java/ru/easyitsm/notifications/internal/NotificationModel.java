package ru.easyitsm.notifications.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Модель шаблона. Конфиденциальная заявка (ACC-04) маскируется здесь, до шаблона: название заменяется
 * нейтральным, текст ответа и причины стираются (NTF-05). Поэтому никакой шаблон — встроенный или изменённый
 * администратором — не может вывести их в письмо, push или центр уведомлений.
 */
final class NotificationModel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private NotificationModel() {
    }

    static Map<String, String> ticket(TicketSnapshot t, String locale, String link) {
        boolean en = "en".equals(locale);
        Map<String, String> m = new HashMap<>();
        m.put("ticket.number", t.number());
        m.put("ticket.title", t.title());
        m.put("ticket.link", link);
        m.put("ticket.status", status(t.statusCategory(), en));
        m.put("ticket.priority", t.priority() == null ? "" : t.priority().code().toUpperCase());
        m.put("confidential", "");
        if (t.confidential()) {
            maskConfidential(m, locale);
        }
        return m;
    }

    static void maskConfidential(Map<String, String> m, String locale) {
        m.put("ticket.title", "en".equals(locale) ? "Confidential ticket" : "Конфиденциальная заявка");
        m.put("comment.body", "");
        m.put("reason", "");
        m.put("confidential", "yes");
    }

    /** Значение, которое у конфиденциальной заявки не выводится. */
    static void putSensitive(Map<String, String> m, String key, String value) {
        if (!"yes".equals(m.get("confidential"))) {
            m.put(key, value == null ? "" : value);
        }
    }

    static String time(Instant at, String timezone) {
        if (at == null) {
            return "";
        }
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank() ? ZoneId.of("Europe/Moscow") : ZoneId.of(timezone);
        } catch (RuntimeException e) {
            zone = ZoneId.of("Europe/Moscow");
        }
        return TIME.format(at.atZone(zone));
    }

    static String status(StatusCategory c, boolean en) {
        if (c == null) {
            return "";
        }
        return switch (c) {
            case DRAFT -> en ? "Draft" : "Черновик";
            case NEW -> en ? "New" : "Новая";
            case APPROVAL -> en ? "Awaiting approval" : "На согласовании";
            case ASSIGNED -> en ? "Assigned" : "Назначена";
            case IN_PROGRESS -> en ? "In progress" : "В работе";
            case PENDING -> en ? "Pending" : "Ожидание";
            case RESOLVED -> en ? "Resolved" : "Решена";
            case CLOSED -> en ? "Closed" : "Закрыта";
            case CANCELLED -> en ? "Cancelled" : "Отменена";
        };
    }

    static String metric(String metric, boolean en) {
        if (metric == null) {
            return "";
        }
        return switch (metric) {
            case "response", "first_response" -> en ? "response" : "реакция";
            case "resolution" -> en ? "resolution" : "решение";
            default -> metric;
        };
    }
}
