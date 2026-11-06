package ru.easyitsm.reporting.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import ru.easyitsm.reporting.internal.ExportModel.Cell;
import ru.easyitsm.reporting.internal.ExportModel.Table;

/**
 * CSV для Excel и LibreOffice: UTF-8 с BOM, разделитель «;», переводы строк CRLF — как выгрузка очереди агента.
 * Таблицы дашборда идут друг за другом с заголовком и пустой строкой между ними.
 */
final class CsvWriter {

    private CsvWriter() {
    }

    static byte[] write(ExportModel model) {
        StringBuilder out = new StringBuilder("﻿");
        line(out, List.of(FormulaGuard.text(model.title())));
        for (String h : model.header()) {
            line(out, List.of(FormulaGuard.text(h)));
        }
        for (Table t : model.tables()) {
            out.append("\r\n");
            if (model.tables().size() > 1) {
                line(out, List.of(FormulaGuard.text(t.title())));
            }
            line(out, t.columns().stream().map(FormulaGuard::text).toList());
            for (List<Cell> row : t.rows()) {
                line(out, row.stream().map(c -> value(c, model.lang())).toList());
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String value(Cell c, String lang) {
        String v = ExportModel.display(c, lang, false);
        // Числа не экранируются: отрицательных значений в отчётах нет, а «-» в начале числа не формула.
        return ReportViews.TEXT.equals(c.type()) || ReportViews.DATE.equals(c.type()) ? FormulaGuard.text(v) : v;
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(';');
            }
            out.append(quote(cells.get(i)));
        }
        out.append("\r\n");
    }

    static String quote(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
