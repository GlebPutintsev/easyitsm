package ru.easyitsm.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.easyitsm.reporting.internal.ExportModel.Cell;
import ru.easyitsm.reporting.internal.ExportModel.Table;

/** Защита от формул (T-17) и форматы значений в выгрузках, без контекста Spring. */
class ExportFormatTest {

    @Test
    void dangerousTextGetsApostrophe() {
        assertThat(FormulaGuard.text("=1+1")).isEqualTo("'=1+1");
        assertThat(FormulaGuard.text("+7 999")).isEqualTo("'+7 999");
        assertThat(FormulaGuard.text("-не работает")).isEqualTo("'-не работает");
        assertThat(FormulaGuard.text("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(FormulaGuard.text("\t=cmd")).isEqualTo("'\t=cmd");
        assertThat(FormulaGuard.text("  =1")).isEqualTo("'  =1");
        assertThat(FormulaGuard.text("＝1")).isEqualTo("'＝1");
        assertThat(FormulaGuard.text("Почта")).isEqualTo("Почта");
        assertThat(FormulaGuard.text("")).isEmpty();
        assertThat(FormulaGuard.text(null)).isEmpty();
    }

    @Test
    void csvQuotesSeparatorsAndKeepsNumbersPlain() {
        ExportModel m = new ExportModel("Отчёт", "ru", List.of("Период: 2026-01-01 — 2026-01-31"), List.of(
                new Table("T", List.of("Тема", "Заявок", "Доля"), List.of("text", "number", "percent"),
                        List.of(List.of(new Cell("a;b \"c\"", "text"), new Cell(-5L, "number"),
                                new Cell(93.4, "percent"))))));
        String csv = new String(CsvWriter.write(m), StandardCharsets.UTF_8);
        assertThat(csv).contains("\"a;b \"\"c\"\"\";-5;93,4\r\n");
    }

    @Test
    void durationsAreHumanInPdfAndHoursInSheets() {
        assertThat(ExportModel.duration(2 * 3600 + 15 * 60, "ru")).isEqualTo("2 ч 15 мин");
        assertThat(ExportModel.duration(26 * 3600, "en")).isEqualTo("1 d 2 h");
        assertThat(ExportModel.duration(59, "ru")).isEqualTo("1 мин");
        assertThat(ExportModel.display(new Cell(5400L, "duration"), "ru", false)).isEqualTo("1,50");
        assertThat(ExportModel.display(new Cell(null, "duration"), "ru", true)).isEqualTo("—");
    }

    @Test
    void pdfRendersCyrillicAndLongTables() {
        List<List<Cell>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(List.of(new Cell("Заявка " + i + " 😀 очень длинная тема ".repeat(5), "text"), new Cell((long) i, "number")));
        }
        byte[] pdf = PdfWriter.write(new ExportModel("Сводка", "ru", List.of("Период"),
                List.of(new Table("Заявки", List.of("Тема", "Заявок"), List.of("text", "number"), rows))));
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
