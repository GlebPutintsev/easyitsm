package ru.easyitsm.knowledge.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Встроенные шаблоны статей (KB-01). Названия переводит интерфейс по коду, тело — Markdown.
 * TODO(управление): шаблоны арендатора в пространстве «Управление».
 */
final class ArticleTemplates {

    static final String SOLUTION = "solution";

    private static final Map<String, String> BODIES = new LinkedHashMap<>();

    static {
        BODIES.put("blank", "");
        BODIES.put("howto", """
                ## Назначение

                Кому и когда пригодится эта инструкция.

                ## Шаги

                1.\s
                2.\s
                3.\s

                ## Результат

                Как понять, что всё получилось.
                """);
        BODIES.put(SOLUTION, """
                ## Симптомы

                ## Причина

                ## Решение

                ## Применимость

                Система, версия, подразделение.
                """);
        BODIES.put("faq", """
                ## Вопрос

                ## Ответ
                """);
        BODIES.put("known_error", """
                ## Описание ошибки

                ## Обходное решение

                ## Статус исправления
                """);
    }

    private ArticleTemplates() {
    }

    static String body(String code) {
        return code == null ? "" : BODIES.getOrDefault(code, "");
    }

    static List<KnowledgeDtos.TemplateView> all() {
        return BODIES.entrySet().stream().map(e -> new KnowledgeDtos.TemplateView(e.getKey(), e.getValue())).toList();
    }

    /** Текст статьи из решённой заявки (KB-05) по шаблону «Решение проблемы». */
    static String fromTicket(String symptoms, String cause, String solution) {
        return "## Симптомы\n\n" + blankIfNull(symptoms) + "\n\n## Причина\n\n" + blankIfNull(cause)
                + "\n\n## Решение\n\n" + blankIfNull(solution) + "\n";
    }

    private static String blankIfNull(String s) {
        return s == null ? "" : s.strip();
    }
}
