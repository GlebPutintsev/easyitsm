package ru.easyitsm.reporting.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ru.easyitsm.reporting.internal.ExportModel.Cell;
import ru.easyitsm.reporting.internal.ExportModel.Table;

/**
 * XLSX (Office Open XML) без сторонних библиотек: по листу на таблицу. Текст пишется встроенными строками
 * ({@code t="inlineStr"}) — редактор не вычисляет их как формулы; дополнительно текст проходит
 * {@link FormulaGuard}, чтобы формула не ожила при правке ячейки. Формул в файле нет.
 */
final class XlsxWriter {

    /** Индексы стилей из {@link #STYLES}. */
    private static final int BOLD = 1;
    private static final int PERCENT = 2;
    private static final int DECIMAL = 3;
    private static final int TITLE = 4;

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <numFmts count="1"><numFmt numFmtId="164" formatCode="0.0%"/></numFmts>
            <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font></fonts>
            <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="5">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
            <xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
            <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
            </cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;

    private XlsxWriter() {
    }

    static byte[] write(ExportModel model) {
        List<String> names = sheetNames(model);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(names.size()));
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            put(zip, "xl/workbook.xml", workbook(names));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(names.size()));
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < model.tables().size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(model, model.tables().get(i)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(ExportModel model, Table t) {
        StringBuilder rows = new StringBuilder();
        int r = 1;
        rows.append(row(r++, List.of(text(model.title(), TITLE))));
        for (String h : model.header()) {
            rows.append(row(r++, List.of(text(h, 0))));
        }
        if (model.tables().size() > 1) {
            r++;
            rows.append(row(r++, List.of(text(t.title(), BOLD))));
        }
        r++;
        List<String> header = new ArrayList<>();
        for (String c : t.columns()) {
            header.add(text(c, BOLD));
        }
        int headerRow = r;
        rows.append(row(r++, header));
        int[] widths = new int[t.columns().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = Math.max(8, t.columns().get(i).length() + 2);
        }
        for (List<Cell> data : t.rows()) {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                Cell c = data.get(i);
                cells.add(cell(c, model.lang()));
                int len = ExportModel.display(c, model.lang(), true).length() + 2;
                widths[i] = Math.min(60, Math.max(widths[i], len));
            }
            rows.append(row(r++, cells));
        }
        StringBuilder cols = new StringBuilder("<cols>");
        for (int i = 0; i < widths.length; i++) {
            cols.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(widths[i]).append("\" customWidth=\"1\"/>");
        }
        cols.append("</cols>");
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetViews><sheetView workbookViewId="0"><pane ySplit="%d" topLeftCell="A%d" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                %s<sheetData>%s</sheetData>
                </worksheet>
                """.formatted(headerRow, headerRow + 1, cols, rows);
    }

    /** Ячейка по типу значения. Строка — {@code <c t="inlineStr">}, ссылка на ячейку не нужна. */
    private static String cell(Cell c, String lang) {
        Object v = c.value();
        if (v == null) {
            return "<c/>";
        }
        return switch (c.type()) {
            case ReportViews.NUMBER -> v instanceof Number n ? number(n.doubleValue(), 0) : text(String.valueOf(v), 0);
            case ReportViews.PERCENT -> number(((Number) v).doubleValue() / 100.0, PERCENT);
            case ReportViews.DURATION -> number(Math.round(((Number) v).doubleValue() / 36.0) / 100.0, DECIMAL);
            case ReportViews.SCORE -> number(((Number) v).doubleValue(), DECIMAL);
            default -> text(String.valueOf(v), 0);
        };
    }

    private static String number(double v, int style) {
        String num = v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
        return "<c" + (style == 0 ? "" : " s=\"" + style + "\"") + "><v>" + num + "</v></c>";
    }

    private static String text(String v, int style) {
        return "<c t=\"inlineStr\"" + (style == 0 ? "" : " s=\"" + style + "\"") + "><is><t xml:space=\"preserve\">"
                + xml(FormulaGuard.text(v)) + "</t></is></c>";
    }

    private static String row(int index, List<String> cells) {
        return "<row r=\"" + index + "\">" + String.join("", cells) + "</row>";
    }

    /** Экранирование XML и удаление управляющих символов, недопустимых в XML 1.0. */
    static String xml(String v) {
        StringBuilder out = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Имена листов: до 31 символа, без {@code []:*?/\}, уникальные. */
    static List<String> sheetNames(ExportModel model) {
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Table t : model.tables()) {
            String base = t.title().replaceAll("[\\[\\]:*?/\\\\']", " ").strip();
            if (base.isEmpty()) {
                base = "Sheet";
            }
            base = base.length() > 28 ? base.substring(0, 28) : base;
            String name = base;
            for (int n = 2; !used.add(name.toLowerCase(java.util.Locale.ROOT)); n++) {
                name = base + " " + n;
            }
            out.add(name);
        }
        return out;
    }

    private static String contentTypes(int sheets) {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                """);
        for (int i = 1; i <= sheets; i++) {
            s.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n");
        }
        return s.append("</Types>\n").toString();
    }

    private static String workbook(List<String> names) {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>""");
        for (int i = 0; i < names.size(); i++) {
            s.append("<sheet name=\"").append(xml(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return s.append("</sheets>\n</workbook>\n").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder s = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                """);
        for (int i = 1; i <= sheets; i++) {
            s.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>\n");
        }
        s.append("<Relationship Id=\"rId").append(sheets + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n");
        return s.append("</Relationships>\n").toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
