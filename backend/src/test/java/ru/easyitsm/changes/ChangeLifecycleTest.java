package ru.easyitsm.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Жизненный цикл обычного изменения (CHG-01…04, CHG-11), разделение обязанностей (AT-05), выгрузки (CHG-09). */
class ChangeLifecycleTest extends ChangeApiSupport {

    @BeforeEach
    void people() {
        users(author, manager, manager2, member);
    }

    @Test
    void normalChangeGoesFromDraftToClosed() throws Exception {
        String created = create(author, "AGT", """
                {"type":"normal","title":"Обновление ЛК","serviceIds":["%s"]}""".formatted(service));
        String id = read(created, "$.id");
        assertThat((String) read(created, "$.number")).startsWith("CHG-");
        assertThat((String) read(created, "$.state")).isEqualTo("draft");
        List<String> missing = read(created, "$.missing");
        assertThat(missing).contains("goal", "justification", "window", "plan.implement", "plan.rollback");

        // Без обязательных полей на оценку не уйти (CHG-02).
        action(id, "/submit", author, "AGT", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fields_required"));

        perform(change(patch("/api/v1/changes/" + id), id,
                author, "AGT").content("{\"goal\":\"Обновить ЛК до 2.4\"}")).andExpect(status().isOk());
        fill(id);
        String view = fetch(id, author, "AGT");
        assertThat((List<?>) read(view, "$.missing")).isEmpty();
        assertThat((Integer) read(view, "$.plan.implementMinutes")).isEqualTo(35);
        assertThat((Boolean) read(view, "$.plan.fitsWindow")).isTrue();

        action(id, "/submit", author, "AGT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("assessment"));
        // Риск без ответов не оценить; по ответам — низкий.
        perform(change(put("/api/v1/changes/" + id + "/risk"), id,
                manager, "CHG").content("{\"answers\":{\"users\":\"none\"}}")).andExpect(status().isBadRequest());
        perform(change(put("/api/v1/changes/" + id + "/risk"), id,
                manager, "CHG").content("{\"answers\":" + LOW_RISK + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.risk.level").value("low"));
        // Отправить на согласование может только менеджер изменений.
        action(id, "/send-to-approval", author, "AGT", null).andExpect(status().isForbidden());
        String approval = body(action(id, "/send-to-approval", manager, "CHG", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("approval")));
        List<String> kinds = read(approval, "$.approvals[*].kind");
        assertThat(kinds).containsExactly("manager");
        String slot = read(approval, "$.approvals[0].id");

        // AT-05: автор, даже с ролью менеджера изменений, своё изменение не согласует.
        action(id, "/approvals/" + slot + "/decision", author, "AGT,CHG", "{\"decision\":\"approve\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("separation_of_duties"));
        // Отказ без комментария не принимается.
        action(id, "/approvals/" + slot + "/decision", manager2, "CHG", "{\"decision\":\"reject\"}")
                .andExpect(status().isBadRequest());
        action(id, "/approvals/" + slot + "/decision", manager2, "CHG", "{\"decision\":\"approve\",\"comment\":\"Ок\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("scheduled"));

        // Заявка идёт за изменением: статус ставит только модуль изменений.
        perform(as(get("/api/v1/tickets/" + id), manager, "CHG")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status.code").value("scheduled"));

        // Окно ещё не наступило: начать можно только с причиной.
        action(id, "/start", author, "AGT", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("outside_window"));
        String started = body(action(id, "/start", author, "AGT", "{\"outsideWindowReason\":\"Согласовано досрочно\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("implementation")));
        // Шаги возврата не отмечаются, пока возврат не начат; на проверку — только когда шаги отмечены.
        List<String> rollbackSteps = read(started, "$.steps[?(@.phase == 'rollback')].id");
        String rollbackStep = rollbackSteps.getFirst();
        action(id, "/steps/" + rollbackStep + "/result", author, "AGT", "{\"status\":\"done\"}")
                .andExpect(status().isConflict());
        action(id, "/verify", author, "AGT", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("steps_pending"));
        List<String> implementSteps = read(started, "$.steps[?(@.phase == 'implement')].id");
        for (String step : implementSteps) {
            action(id, "/steps/" + step + "/result", author, "AGT", "{\"status\":\"done\"}").andExpect(status().isOk());
        }
        action(id, "/verify", author, "AGT", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("verification"));
        action(id, "/complete", author, "AGT", "{\"outcome\":\"successful\",\"conclusion\":\"Релиз установлен\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("review"));

        // Разбор после внедрения (CHG-11): связанные инциденты и закрытие менеджером.
        perform(as(get("/api/v1/changes/" + id + "/related-incidents"), manager, "CHG")).andExpect(status().isOk());
        action(id, "/close", author, "AGT", "{\"answers\":{},\"conclusion\":\"Цель достигнута\"}")
                .andExpect(status().isForbidden());
        action(id, "/close", manager, "CHG", """
                {"answers":{"goalAchieved":"yes","causedIncidents":"no","onTime":"yes"},"conclusion":"Цель достигнута"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("closed"))
                .andExpect(jsonPath("$.review.goalAchieved").value("yes"));
        perform(as(get("/api/v1/tickets/" + id), manager, "CHG")).andExpect(jsonPath("$.status.code").value("closed"));
    }

    @Test
    void foreignTenantAndOutsidersDoNotSeeChange() throws Exception {
        String id = readyNormal("Скрытое изменение");
        perform(as(get("/api/v1/changes/" + id), manager, "CHG", OTHER)).andExpect(status().isNotFound());
        // Обычный пользователь портала к изменениям не допущен.
        perform(as(get("/api/v1/changes/" + id), member, "REQ")).andExpect(status().isForbidden());
        // Сотрудник поддержки видит расписание изменений, но менять чужое не может.
        perform(as(get("/api/v1/changes/" + id), member, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.edit").value(false));
        perform(change(patch("/api/v1/changes/" + id), id,
                member, "AGT").content("{\"goal\":\"Чужая цель\"}")).andExpect(status().isForbidden());
    }

    @Test
    void cancelOnlyBeforeImplementation() throws Exception {
        String id = readyNormal("Отменяемое изменение");
        action(id, "/cancel", author, "AGT", "{\"reason\":\"Не актуально\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("cancelled"));
        action(id, "/submit", author, "AGT", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_state"));
    }

    @Test
    void planExportsOpenAsDocuments() throws Exception {
        String id = readyNormal("Выгрузка плана");
        byte[] xlsx = perform(as(get("/api/v1/changes/" + id + "/plan-document?format=xlsx"), author, "AGT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        Set<String> parts = zipEntries(xlsx);
        assertThat(parts).contains("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml");
        assertThat(zipText(xlsx, "xl/worksheets/sheet1.xml")).contains("Установить релиз 2.4", "Вернуть релиз 2.3");

        byte[] docx = perform(as(get("/api/v1/changes/" + id + "/plan-document?format=docx"), author, "AGT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(zipText(docx, "word/document.xml")).contains("Установить релиз 2.4", "Проверить вход");

        byte[] pdf = perform(as(get("/api/v1/changes/" + id + "/plan-document?format=pdf"), author, "AGT"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        perform(as(get("/api/v1/changes/" + id + "/plan-document?format=exe"), author, "AGT"))
                .andExpect(status().isBadRequest());
        perform(as(get("/api/v1/changes/" + id + "/plan-document"), manager, "CHG", OTHER))
                .andExpect(status().isNotFound());

        String ics = perform(as(get("/api/v1/changes/" + id + "/ical"), author, "AGT")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(ics).startsWith("BEGIN:VCALENDAR").contains("BEGIN:VEVENT", "END:VCALENDAR");
    }

    @Test
    void riskQuestionsArePublishedForTheForm() throws Exception {
        perform(as(get("/api/v1/changes/risk-questions"), author, "AGT")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("users"));
        perform(as(post("/api/v1/changes"), author, "AGT").content("{\"type\":\"other\",\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    private static Set<String> zipEntries(byte[] zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static String zipText(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("Нет части " + name);
    }
}
