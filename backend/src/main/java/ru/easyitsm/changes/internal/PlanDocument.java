package ru.easyitsm.changes.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.easyitsm.changes.api.ChangeType;
import ru.easyitsm.changes.api.RiskLevel;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;

/**
 * План работ изменения в виде документа (CHG-09): шапка и разделы согласованной формы «Поэтапный план работ по
 * внедрению обновлений» — тема, инициатор, затронутые сервисы, причина, негативные события, ответственный, влияние,
 * суть изменений, план внедрения (подготовка и внедрение), план проверки, план возврата и отчёт о выполнении работ.
 * Один и тот же документ пишут {@link PlanXlsx}, {@link PlanDocx} и {@link PlanPdf}.
 */
@Component
class PlanDocument {

    /** Колонки плана работ, как в форме: от номера до критерия успешности. */
    static final List<String> STEP_COLUMNS = List.of("#", "Краткое наименование работ", "Описание работ",
            "Ответственный", "Рабочая группа", "Контакты ответственного", "Длительность", "Время начала",
            "Время окончания", "Возможные прерывания сервиса и длительность", "Критерий успешности");
    /** Относительная ширина колонок плана. */
    static final List<Integer> STEP_WEIGHTS = List.of(3, 14, 26, 11, 8, 12, 6, 8, 8, 10, 12);

    static final List<String> IMPACT_COLUMNS = List.of("#", "ИТ-сервис или система", "Описание влияния",
            "Длительность влияния");
    static final List<Integer> IMPACT_WEIGHTS = List.of(3, 30, 50, 17);

    /** Вопросы отчёта о выполнении работ и ключи ответов разбора (CHG-11). */
    static final List<Map.Entry<String, String>> REPORT = List.of(
            Map.entry("goalAchieved", "Внесение изменения достигло цели? ДА/НЕТ"),
            Map.entry("enoughInfo", "Было ли достаточно информации для проведения работ?"),
            Map.entry("causedIncidents", "Вызвали ли работы инциденты? ДА/НЕТ"),
            Map.entry("incidentNumbers", "Номера возникших инцидентов"),
            Map.entry("incidentCauses", "В чём причины возникновения инцидента?"),
            Map.entry("consequences", "Как последствия повлияли на функциональность, доступность, производительность?"),
            Map.entry("correctiveActions", "Мероприятия по недопущению инцидента, план корректирующих действий"),
            Map.entry("onTime", "Работы выполнены в утверждённое время? ДА/НЕТ"),
            Map.entry("impactAsPlanned", "Соответствовало ли время влияния работ на бизнес запланированному?"),
            Map.entry("docsUpdated", "Была ли обновлена документация, база знаний, CMDB?"),
            Map.entry("rollbackWorked", "Если применялся план возврата, сработал ли он?"),
            Map.entry("comment", "Комментарий"));

    sealed interface Block permits Field, Heading, Table {
    }

    /** Пара «подпись — значение»; {@code number} — номер раздела формы или пусто. */
    record Field(String number, String label, String value) implements Block {
    }

    record Heading(String number, String text) implements Block {
    }

    /**
     * Таблица; строка с одной ячейкой — заголовок подраздела во всю ширину («ПОДГОТОВКА», «ВНЕДРЕНИЕ»).
     */
    record Table(List<String> columns, List<Integer> weights, List<List<String>> rows) implements Block {
    }

    record Sheet(String name, List<Block> blocks) {
    }

    record Doc(String title, String fileName, List<Sheet> sheets) {
    }

    private final ChangeStore store;
    private final UserDirectory directory;
    private final ConfigurationItems cmdb;

    PlanDocument(ChangeStore store, UserDirectory directory, ConfigurationItems cmdb) {
        this.store = store;
        this.directory = directory;
        this.cmdb = cmdb;
    }

    Doc build(ChangeRow c) {
        List<ChangeStore.Step> steps = store.steps(c.id);
        PlanSchedule.Result plan = PlanSchedule.compute(c, steps);
        ZoneId zone = ZoneId.of(c.timeZone);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone);

        Set<UUID> people = new HashSet<>();
        people.add(c.authorId);
        if (c.assigneeId != null) {
            people.add(c.assigneeId);
        }
        steps.stream().map(ChangeStore.Step::ownerId).filter(java.util.Objects::nonNull).forEach(people::add);
        Map<UUID, UserSummary> users = directory.findUsers(people).stream()
                .collect(Collectors.toMap(UserSummary::id, Function.identity(), (a, b) -> a));
        Map<UUID, String> names = new LinkedHashMap<>();
        for (ConfigurationItems.Ci ci : cmdb.find(union(c.ciIds, c.serviceIds))) {
            names.put(ci.id(), ci.name());
        }

        List<Block> blocks = new ArrayList<>();
        blocks.add(new Field("", "Запрос на изменение №", c.number));
        blocks.add(new Field("", "Влияние изменения", impactLabel(c)));
        blocks.add(new Field("", "Уровень критичности ИС", criticality(c.systemCriticality)));
        blocks.add(new Field("", "Плановая дата и длительность проведения изменения",
                (c.windowStart == null ? "—" : "Начало: " + fmt.format(c.windowStart))
                        + (c.windowEnd == null ? "" : "\nЗавершение: " + fmt.format(c.windowEnd))
                        + "\nЧасовой пояс: " + c.timeZone));
        blocks.add(new Field("", "Время недоступности ИТ-сервиса", downtime(c.downtimeMinutes)));
        blocks.add(new Field("", "Тип изменения", typeLabel(c.type)
                + (c.riskLevel == null ? "" : ", риск " + riskLabel(c.riskLevel)
                + (c.riskScore == null ? "" : " (балл " + c.riskScore + ")"))));
        blocks.add(new Field("1", "Тема", c.title));
        blocks.add(new Field("2", "Инициатор изменения", person(users.get(c.authorId))));
        blocks.add(new Field("3", "Затронутые ИТ-сервисы", affected(c, names)));
        blocks.add(new Field("4", "Причина внесения изменения", c.justification));
        blocks.add(new Field("5", "Негативные события, которые могут возникнуть при внедрении изменения", c.risks));
        blocks.add(new Field("6", "Ответственный за применение изменения",
                c.assigneeId == null ? "—" : person(users.get(c.assigneeId))));
        blocks.add(new Field("7", "Влияние внедрения изменения на ИТ-сервисы или информационные системы", c.impact));
        if (!c.impactRows.isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            int i = 1;
            for (Map<String, Object> r : c.impactRows) {
                rows.add(List.of(String.valueOf(i++), str(r.get("service")), str(r.get("description")),
                        str(r.get("duration"))));
            }
            blocks.add(new Table(IMPACT_COLUMNS, IMPACT_WEIGHTS, rows));
        }
        blocks.add(new Field("8", "Суть проводимых изменений", c.goal));

        blocks.add(new Heading("9", "План внедрения изменения"));
        List<List<String>> implementRows = new ArrayList<>();
        appendPhase(implementRows, "ПОДГОТОВКА", "prepare", plan.steps(), users, fmt);
        appendPhase(implementRows, "ВНЕДРЕНИЕ", "implement", plan.steps(), users, fmt);
        blocks.add(new Table(STEP_COLUMNS, STEP_WEIGHTS, implementRows));

        blocks.add(new Heading("10", "План проверки работоспособности"));
        List<List<String>> verifyRows = new ArrayList<>();
        appendPhase(verifyRows, null, "verify", plan.steps(), users, fmt);
        blocks.add(new Table(STEP_COLUMNS, STEP_WEIGHTS, verifyRows));

        blocks.add(new Heading("11", "План возврата (последовательность действий при неудачном внедрении)"));
        List<List<String>> rollbackRows = new ArrayList<>();
        appendPhase(rollbackRows, null, "rollback", plan.steps(), users, fmt);
        blocks.add(new Table(STEP_COLUMNS, STEP_WEIGHTS, rollbackRows));
        blocks.add(new Field("", "Длительность плана возврата", minutes(plan.totals().rollbackMinutes())));

        List<Block> report = new ArrayList<>();
        report.add(new Heading("", "Отчёт о выполнении работ по внесению изменений"));
        if (c.outcome != null) {
            report.add(new Field("", "Результат", ChangeService.outcomeLabel(c.outcome)));
            report.add(new Field("", "Фактическое окно",
                    (c.actualStart == null ? "—" : fmt.format(c.actualStart)) + " — "
                            + (c.actualEnd == null ? "—" : fmt.format(c.actualEnd))));
        }
        List<List<String>> answers = new ArrayList<>();
        for (Map.Entry<String, String> q : REPORT) {
            answers.add(List.of(q.getValue(), answer(c.review.get(q.getKey()))));
        }
        report.add(new Table(List.of("Вопрос", "Ответ"), List.of(55, 45), answers));
        if (c.conclusion != null) {
            report.add(new Field("", "Заключение", c.conclusion));
        }

        String title = "Поэтапный план работ по внедрению обновлений " + c.number;
        return new Doc(title, "План работ " + c.number, List.of(new Sheet("План работ", blocks),
                new Sheet("Отчёт о выполнении работ", report)));
    }

    private static void appendPhase(List<List<String>> rows, String section, String phase,
                                    List<ChangeDtos.StepView> steps, Map<UUID, UserSummary> users,
                                    DateTimeFormatter fmt) {
        List<ChangeDtos.StepView> list = steps.stream().filter(s -> phase.equals(s.phase())).toList();
        if (section != null) {
            rows.add(List.of(section));
        }
        for (ChangeDtos.StepView s : list) {
            UserSummary owner = s.ownerId() == null ? null : users.get(s.ownerId());
            String ownerName = owner != null ? owner.displayName() : str(s.ownerName());
            String contacts = s.contacts() != null ? s.contacts() : owner != null && owner.email() != null ? owner.email() : "";
            String duration = (s.upto() ? "до " : "") + minutes(s.durationMinutes());
            rows.add(List.of(String.valueOf(s.seq()), s.title(), str(s.description()), ownerName, str(s.team()), contacts,
                    duration, s.start() == null ? "" : fmt.format(s.start()), s.end() == null ? "" : fmt.format(s.end()),
                    s.interruption() == null ? "нет" : s.interruption(), str(s.checkCriteria())));
        }
    }

    static String impactLabel(ChangeRow c) {
        if (c.type == ChangeType.EMERGENCY) {
            return "Экстренное";
        }
        if ((c.downtimeMinutes != null && c.downtimeMinutes > 0) || c.riskLevel == RiskLevel.HIGH) {
            return "С существенным влиянием";
        }
        if (c.riskLevel == RiskLevel.MEDIUM) {
            return "Без существенного влияния";
        }
        return "С низким влиянием";
    }

    private static String criticality(String v) {
        if (v == null) {
            return "—";
        }
        return switch (v) {
            case "critical" -> "Критичный";
            case "high" -> "Высокий";
            case "medium" -> "Средний";
            default -> "Низкий";
        };
    }

    static String typeLabel(ChangeType t) {
        return switch (t) {
            case STANDARD -> "Стандартное";
            case NORMAL -> "Нормальное";
            case EMERGENCY -> "Экстренное";
        };
    }

    private static String riskLabel(RiskLevel l) {
        return switch (l) {
            case LOW -> "низкий";
            case MEDIUM -> "средний";
            case HIGH -> "высокий";
        };
    }

    private static String downtime(Integer m) {
        if (m == null) {
            return "—";
        }
        return m == 0 ? "-" : "до " + m + " мин.";
    }

    static String minutes(int m) {
        if (m < 60) {
            return m + " мин";
        }
        return (m / 60) + " ч" + (m % 60 == 0 ? "" : " " + (m % 60) + " мин");
    }

    private static String person(UserSummary u) {
        if (u == null) {
            return "—";
        }
        return u.displayName() + (u.email() == null || u.email().isBlank() ? "" : ", " + u.email());
    }

    private static String affected(ChangeRow c, Map<UUID, String> names) {
        List<String> lines = new ArrayList<>();
        for (UUID id : c.serviceIds) {
            lines.add(names.getOrDefault(id, "Услуга " + id));
        }
        for (UUID id : c.ciIds) {
            lines.add(names.getOrDefault(id, "КЕ " + id));
        }
        for (Map<String, Object> r : c.impactRows) {
            String s = str(r.get("service"));
            if (!s.isBlank() && !lines.contains(s)) {
                lines.add(s);
            }
        }
        return lines.isEmpty() ? "—" : String.join("\n", lines);
    }

    private static Collection<UUID> union(List<UUID> a, List<UUID> b) {
        Set<UUID> s = new HashSet<>(a);
        s.addAll(b);
        return s;
    }

    private static String answer(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "ДА" : "НЕТ";
        }
        if (v instanceof Collection<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return String.valueOf(v);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    static String safeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static Instant now() {
        return Instant.now();
    }
}
