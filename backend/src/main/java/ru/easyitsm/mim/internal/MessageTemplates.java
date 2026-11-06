package ru.easyitsm.mim.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Шаблоны сообщений (MIM-04): готовые для пользователей и руководства заводятся при первом обращении арендатора,
 * дальше их правит MIM или администратор. Переменные — {@code {{number}}}, {@code {{title}}}, {@code {{services}}},
 * {@code {{status}}}, {@code {{hypothesis}}}, {@code {{nextUpdate}}}, {@code {{startedAt}}}, {@code {{duration}}};
 * неизвестная остаётся как есть.
 */
@Component
class MessageTemplates {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"));

    record Default(String name, String audience, String title, String body) {
    }

    static final List<Default> DEFAULTS = List.of(
            new Default("Пользователям: сбой обнаружен", "users", "Сбой: {{title}}",
                    "Мы знаем о сбое: {{title}}. Затронуто: {{services}}.\n\nСпециалисты уже работают над "
                            + "восстановлением. Следующее сообщение — {{nextUpdate}}.\n\nНовую заявку о том же "
                            + "создавать не нужно."),
            new Default("Пользователям: ход работ", "users", "Обновление: {{title}}",
                    "Работы по восстановлению продолжаются. Затронуто: {{services}}.\n\nСледующее сообщение — "
                            + "{{nextUpdate}}."),
            new Default("Пользователям: сервис восстановлен", "users", "Восстановлено: {{title}}",
                    "Работа {{services}} восстановлена. Если проблема осталась, ответьте в своей заявке или "
                            + "создайте новую."),
            new Default("Руководству: сводка", "management", "{{number}}: {{title}} — {{status}}",
                    "Крупный инцидент {{number}} с {{startedAt}} ({{duration}}).\nЗатронуто: {{services}}.\n"
                            + "Состояние: {{status}}.\nТекущая гипотеза: {{hypothesis}}.\nСледующая сводка — "
                            + "{{nextUpdate}}."));

    private final MimStore store;

    MessageTemplates(MimStore store) {
        this.store = store;
    }

    /** Шаблоны арендатора; если их ещё нет — заводит готовые. */
    List<MimStore.Template> all(boolean onlyActive, UUID actor) {
        List<MimStore.Template> list = store.templates(false);
        if (list.isEmpty()) {
            int pos = 0;
            for (Default d : DEFAULTS) {
                store.saveTemplate(null, new MimDtos.SaveTemplate(d.name(), d.audience(), d.title(), d.body(),
                        pos++, true), actor);
            }
        }
        return store.templates(onlyActive);
    }

    static String render(String text, Map<String, String> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String time(Instant at, ZoneId zone) {
        return at == null ? "—" : TIME.format(at.atZone(zone));
    }

    static String duration(Instant from, Instant to) {
        if (from == null) {
            return "—";
        }
        long minutes = Math.max(0, java.time.Duration.between(from, to == null ? Instant.now() : to).toMinutes());
        return minutes < 60 ? minutes + " мин" : (minutes / 60) + " ч " + (minutes % 60) + " мин";
    }
}
