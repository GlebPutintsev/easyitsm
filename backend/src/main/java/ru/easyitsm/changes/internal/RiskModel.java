package ru.easyitsm.changes.internal;

import java.util.List;
import java.util.Map;
import ru.easyitsm.changes.api.RiskLevel;

/**
 * Анкета риска (CHG-04): вопросы с весами ответов, сумма даёт балл, балл — уровень. Уровень определяет маршрут
 * согласования ({@link ChangeSettings}). Ручная смена уровня требует причины и видна в журнале.
 */
final class RiskModel {

    record Option(String code, String label, int weight) {
    }

    record Question(String code, String text, List<Option> options) {

        Option option(String code) {
            return options.stream().filter(o -> o.code().equals(code)).findFirst().orElse(null);
        }
    }

    record Result(int score, RiskLevel level) {
    }

    /** До этого балла включительно риск низкий. */
    static final int LOW_MAX = 5;
    /** До этого балла включительно риск средний, выше — высокий. */
    static final int MEDIUM_MAX = 11;

    static final List<Question> QUESTIONS = List.of(
            new Question("users", "Сколько пользователей затронет изменение", List.of(
                    new Option("none", "Никого или единицы", 0),
                    new Option("group", "Одно подразделение", 1),
                    new Option("many", "Несколько подразделений", 2),
                    new Option("all", "Все пользователи или внешние клиенты", 3))),
            new Question("service_criticality", "Критичность затронутых услуг", List.of(
                    new Option("low", "Низкая", 0),
                    new Option("medium", "Средняя", 1),
                    new Option("high", "Высокая", 2),
                    new Option("critical", "Критичная", 3))),
            new Question("experience", "Опыт выполнения таких работ", List.of(
                    new Option("routine", "Делали много раз по этому плану", 0),
                    new Option("done_before", "Делали раньше", 1),
                    new Option("first_time", "Делаем впервые", 3))),
            new Question("rollback", "Возврат к исходному состоянию", List.of(
                    new Option("simple", "Быстрый и проверенный", 0),
                    new Option("complex", "Сложный или долгий", 2),
                    new Option("impossible", "Невозможен: необратимые миграции, только восстановление из копии", 3))),
            new Question("downtime", "Простой услуг", List.of(
                    new Option("none", "Без простоя", 0),
                    new Option("within_window", "В пределах окна работ", 1),
                    new Option("beyond", "Возможен простой сверх окна", 3))),
            new Question("testing", "Проверка в тестовой среде", List.of(
                    new Option("full", "Полностью повторено в среде, идентичной продуктивной", 0),
                    new Option("partial", "Проверено частично", 1),
                    new Option("none", "Не проверялось", 3))),
            new Question("dependencies", "Влияние на смежные системы", List.of(
                    new Option("none", "Нет", 0),
                    new Option("few", "Одна-две смежные системы", 1),
                    new Option("many", "Много смежных систем", 2))));

    private RiskModel() {
    }

    /** Проверяет, что ответ дан на каждый вопрос и вариант существует, и считает балл и уровень. */
    static Result evaluate(Map<String, ?> answers) {
        int score = 0;
        for (Question q : QUESTIONS) {
            Object a = answers == null ? null : answers.get(q.code());
            Option o = a == null ? null : q.option(String.valueOf(a));
            if (o == null) {
                throw ChangeProblem.field("answers." + q.code(), "Ответьте на вопрос «" + q.text() + "»");
            }
            score += o.weight();
        }
        if (answers.size() > QUESTIONS.size()) {
            throw ChangeProblem.field("answers", "В анкете есть неизвестные вопросы");
        }
        RiskLevel level = score <= LOW_MAX ? RiskLevel.LOW : score <= MEDIUM_MAX ? RiskLevel.MEDIUM : RiskLevel.HIGH;
        // Необратимое изменение не бывает низкого риска, даже если остальное спокойно.
        if (level == RiskLevel.LOW && "impossible".equals(String.valueOf(answers.get("rollback")))) {
            level = RiskLevel.MEDIUM;
        }
        return new Result(score, level);
    }
}
