package ru.easyitsm.changes.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * План работ в XLSX по сетке формы: 11 колонок плана, разделы с номером, подписью и значением, таблицы с
 * объединёнными ячейками. Без сторонних библиотек. Текст пишется встроенными строками в текстовом формате с
 * {@code quotePrefix}: редактор не вычисляет его как формулу ни при открытии, ни после правки ячейки (RPT-04),
 * а списки «- пункт» остаются без апострофа.
 */
final class PlanXlsx {

    private static final int COLS = 11;
    private static final double[] WIDTHS = {5, 26, 46, 20, 14, 22, 11, 16, 16, 20, 24};

    private static final int BODY = 1;
    private static final int LABEL = 2;
    private static final int HEAD = 3;
    private static final int TITLE = 4;
    private static final int SECTION = 5;

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <fonts count="3"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font><font><b/><sz val="13"/><name val="Arial"/></font></fonts>
            <fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8ECF3"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF4EEDF"/><bgColor indexed="64"/></patternFill></fill></fills>
            <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FF9AA3B2"/></left><right style="thin"><color rgb="FF9AA3B2"/></right><top style="thin"><color rgb="FF9AA3B2"/></top><bottom style="thin"><color rgb="FF9AA3B2"/></bottom><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="6">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="49" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1" applyNumberFormat="1" quotePrefix="1"><alignment vertical="top" wrapText="1"/></xf>
            <xf numFmtId="49" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1" applyNumberFormat="1" quotePrefix="1"><alignment vertical="top" wrapText="1"/></xf>
            <xf numFmtId="49" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1" applyNumberFormat="1" quotePrefix="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
            <xf numFmtId="49" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1" applyNumberFormat="1" quotePrefix="1"><alignment vertical="center" wrapText="1"/></xf>
            <xf numFmtId="49" fontId="1" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1" applyNumberFormat="1" quotePrefix="1"><alignment vertical="center"/></xf>
            </cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
            </styleSheet>
            """;

    private PlanXlsx() {
    }

    static byte[] write(PlanDocument.Doc doc) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int n = doc.sheets().size();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            StringBuilder overrides = new StringBuilder();
            StringBuilder sheets = new StringBuilder();
            StringBuilder rels = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                sheets.append("<sheet name=\"").append(xml(sheetName(doc.sheets().get(i - 1).name())))
                        .append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i).append(".xml\"/>");
            }
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    %s
                    </Types>
                    """.formatted(overrides));
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets>%s</sheets>
                    </workbook>
                    """.formatted(sheets));
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    %s<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """.formatted(rels, n + 1));
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < n; i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(doc, doc.sheets().get(i), i == 0));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String sheet(PlanDocument.Doc doc, PlanDocument.Sheet sheet, boolean first) {
        Grid g = new Grid();
        if (first) {
            g.row(List.of(new Cell(1, COLS, doc.title(), TITLE)), 24);
        } else {
            g.row(List.of(new Cell(1, COLS, sheet.name(), TITLE)), 24);
        }
        for (PlanDocument.Block b : sheet.blocks()) {
            switch (b) {
                case PlanDocument.Field f -> g.row(List.of(new Cell(1, 1, f.number(), LABEL),
                        new Cell(2, 4, f.label(), LABEL), new Cell(5, COLS, f.value() == null || f.value().isBlank() ? "—" : f.value(), BODY)), 0);
                case PlanDocument.Heading h -> g.row(List.of(new Cell(1, 1, h.number(), LABEL),
                        new Cell(2, COLS, h.text(), LABEL)), 0);
                case PlanDocument.Table t -> {
                    int[][] spans = spans(t.weights());
                    List<Cell> head = new ArrayList<>();
                    for (int i = 0; i < t.columns().size(); i++) {
                        head.add(new Cell(spans[i][0], spans[i][1], t.columns().get(i), HEAD));
                    }
                    g.row(head, 0);
                    if (t.rows().isEmpty()) {
                        g.row(List.of(new Cell(1, COLS, "Шагов нет", BODY)), 0);
                    }
                    for (List<String> r : t.rows()) {
                        if (r.size() == 1) {
                            g.row(List.of(new Cell(1, COLS, r.getFirst(), SECTION)), 18);
                            continue;
                        }
                        List<Cell> cells = new ArrayList<>();
                        for (int i = 0; i < r.size(); i++) {
                            cells.add(new Cell(spans[i][0], spans[i][1], r.get(i), BODY));
                        }
                        g.row(cells, 0);
                    }
                }
            }
        }
        StringBuilder cols = new StringBuilder("<cols>");
        for (int i = 0; i < COLS; i++) {
            cols.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(WIDTHS[i]).append("\" customWidth=\"1\"/>");
        }
        cols.append("</cols>");
        String merges = g.merges.isEmpty() ? "" : "<mergeCells count=\"" + g.merges.size() + "\">"
                + String.join("", g.merges) + "</mergeCells>";
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>
                <sheetViews><sheetView workbookViewId="0" zoomScale="90"/></sheetViews>
                %s<sheetData>%s</sheetData>%s
                <pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>
                <pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/>
                </worksheet>
                """.formatted(cols, g.rows, merges);
    }

    /** Делит 11 колонок сетки между колонками таблицы по весам, каждой — не меньше одной. */
    static int[][] spans(List<Integer> weights) {
        int n = weights.size();
        int[][] result = new int[n][2];
        if (n >= COLS) {
            for (int i = 0; i < n; i++) {
                result[i] = new int[]{Math.min(i + 1, COLS), Math.min(i + 1, COLS)};
            }
            return result;
        }
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        int[] size = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            size[i] = Math.max(1, Math.round((float) weights.get(i) * COLS / total));
            used += size[i];
        }
        while (used > COLS) {
            int max = 0;
            for (int i = 1; i < n; i++) {
                if (size[i] > size[max]) {
                    max = i;
                }
            }
            size[max]--;
            used--;
        }
        size[n - 1] += COLS - used;
        int col = 1;
        for (int i = 0; i < n; i++) {
            result[i] = new int[]{col, col + size[i] - 1};
            col += size[i];
        }
        return result;
    }

    private record Cell(int from, int to, String text, int style) {
    }

    private static final class Grid {
        final StringBuilder rows = new StringBuilder();
        final List<String> merges = new ArrayList<>();
        int r = 0;

        void row(List<Cell> cells, double fixedHeight) {
            r++;
            double lines = 1;
            StringBuilder sb = new StringBuilder();
            int col = 1;
            for (Cell c : cells) {
                while (col < c.from()) {
                    sb.append("<c r=\"").append(ref(col, r)).append("\" s=\"").append(BODY).append("\"/>");
                    col++;
                }
                sb.append("<c r=\"").append(ref(c.from(), r)).append("\" t=\"inlineStr\" s=\"").append(c.style())
                        .append("\"><is><t xml:space=\"preserve\">").append(xml(c.text())).append("</t></is></c>");
                col = c.from() + 1;
                double width = 0;
                for (int i = c.from(); i <= c.to(); i++) {
                    width += WIDTHS[i - 1];
                    if (i > c.from()) {
                        // объединённая область тоже получает рамку
                        sb.append("<c r=\"").append(ref(i, r)).append("\" s=\"").append(c.style()).append("\"/>");
                    }
                }
                col = c.to() + 1;
                if (c.to() > c.from()) {
                    merges.add("<mergeCell ref=\"" + ref(c.from(), r) + ":" + ref(c.to(), r) + "\"/>");
                }
                lines = Math.max(lines, estimateLines(c.text(), width));
            }
            double height = fixedHeight > 0 ? fixedHeight : Math.min(409, Math.max(15, lines * 13.5));
            rows.append("<row r=\"").append(r).append("\" ht=\"").append(height).append("\" customHeight=\"1\">")
                    .append(sb).append("</row>");
        }
    }

    /** Сколько строк займёт текст в ячейке ширины {@code width} символов: объединённые ячейки Excel не подбирает сам. */
    static double estimateLines(String text, double width) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        double perLine = Math.max(4, width * 1.1);
        double lines = 0;
        for (String part : text.split("\n", -1)) {
            lines += Math.max(1, Math.ceil(part.length() / perLine));
        }
        return lines;
    }

    static String ref(int col, int row) {
        StringBuilder s = new StringBuilder();
        int c = col;
        while (c > 0) {
            int rem = (c - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return s.toString() + row;
    }

    private static String sheetName(String name) {
        String s = name.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c < 0x20 && c != '\n' && c != '\t') {
                        continue;
                    }
                    b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
