package ru.easyitsm.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;

/** NTF-02 и NTF-05: подстановка, блоки, все встроенные шаблоны и маскирование конфиденциальной заявки. */
class TemplatesTest {

    @Test
    void variablesAndSections() {
        Map<String, String> m = Map.of("a", "Анна", "reason", "", "flag", "yes");
        assertThat(TemplateRenderer.render("Привет, {{a}}!{{#reason}} Причина: {{reason}}{{/reason}}", m))
                .isEqualTo("Привет, Анна!");
        assertThat(TemplateRenderer.render("{{^flag}}скрыто{{/flag}}{{#flag}}видно {{ a }}{{/flag}}", m))
                .isEqualTo("видно Анна");
        assertThat(TemplateRenderer.render("{{unknown}}x", m)).isEqualTo("x");
        // Значение с фигурными скобками не раскрывается повторно.
        assertThat(TemplateRenderer.render("{{a}}", Map.of("a", "{{b}}", "b", "секрет"))).isEqualTo("{{b}}");
        assertThat(TemplateRenderer.subject("Тема\r\nBcc: x@y", Map.of())).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void htmlIsEscaped() {
        String html = TemplateRenderer.html("<script>alert(1)</script>\n\nhttps://itsm.example.com/a?b=1&c=2", "ITSM");
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;")
                .contains("<a href=\"https://itsm.example.com/a?b=1&amp;c=2\"");
    }

    @Test
    void everyKindHasBothLanguagesAndRendersWithSample() {
        for (String kind : Templates.KINDS.keySet()) {
            for (String locale : TemplateStore.LOCALES) {
                Templates.Template t = (locale.equals("en") ? Templates.EN : Templates.RU).get(kind);
                assertThat(t).as(kind + "/" + locale).isNotNull();
                String text = TemplateRenderer.render(t.body(), TemplateStore.sample(locale, false));
                assertThat(text).as(kind).doesNotContain("{{").isNotBlank();
            }
        }
    }

    @Test
    void confidentialTicketLeaksNothingEvenWithCustomTemplate() {
        TicketSnapshot t = new TicketSnapshot(UUID.randomUUID(), "INC-000777", TicketType.INCIDENT,
                "Увольнение Петрова", "new", StatusCategory.NEW, Priority.P3, Level.LOW, Level.MEDIUM, UUID.randomUUID(),
                null, null, null, null, null, null, null, null, Channel.PORTAL, true, null, null, Instant.now(), null,
                null, null, 1);
        Map<String, String> m = new HashMap<>(NotificationModel.ticket(t, "ru", "https://x/portal/tickets/INC-000777"));
        NotificationModel.putSensitive(m, "comment.body", "Приказ подписан");
        NotificationModel.putSensitive(m, "reason", "по соглашению сторон");
        String evil = "{{ticket.title}} {{comment.body}} {{reason}}";
        String out = TemplateRenderer.render(evil, m) + TemplateRenderer.render(Templates.RU.get("ticket_reply").body(), m);
        assertThat(out).doesNotContain("Петров").doesNotContain("Приказ").doesNotContain("соглашению")
                .contains("Конфиденциальная заявка").contains("INC-000777");
    }

    @Test
    void summarySkipsGreetingAndLinks() {
        assertThat(Dispatcher.summary("Здравствуйте, Анна!\n\nЗаявка решена.\n\nhttps://x")).isEqualTo("Заявка решена.");
    }
}
