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
 * План работ в DOCX (WordprocessingML) без сторонних библиотек: альбомный A4, поля раздела формы — таблицей
 * «номер — подпись — значение», планы — таблицами с повтором шапки на каждой странице.
 */
final class PlanDocx {

    /** Ширина текста альбомного A4 с полями 1,27 см, в twips. */
    private static final int TEXT_WIDTH = 16838 - 2 * 720;
    private static final String HEADER_FILL = "E8ECF3";
    private static final String SECTION_FILL = "F4EEDF";

    private PlanDocx() {
    }

    static byte[] write(PlanDocument.Doc doc) {
        StringBuilder body = new StringBuilder();
        body.append(paragraph(doc.title(), true, 28, false));
        boolean firstSheet = true;
        for (PlanDocument.Sheet sheet : doc.sheets()) {
            if (!firstSheet) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
            firstSheet = false;
            List<PlanDocument.Field> fields = new ArrayList<>();
            for (PlanDocument.Block b : sheet.blocks()) {
                if (b instanceof PlanDocument.Field f) {
                    fields.add(f);
                    continue;
                }
                flushFields(body, fields);
                switch (b) {
                    case PlanDocument.Heading h -> body.append(paragraph(
                            (h.number().isEmpty() ? "" : h.number() + ". ") + h.text(), true, 22, true));
                    case PlanDocument.Table t -> body.append(table(t));
                    default -> {
                    }
                }
            }
            flushFields(body, fields);
        }
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s<w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="360" w:footer="360" w:gutter="0"/></w:sectPr></w:body>
                </w:document>
                """.formatted(body);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                    </Types>
                    """);
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            put(zip, "word/_rels/document.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);
            put(zip, "word/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:cs="Arial" w:eastAsia="Arial"/><w:sz w:val="18"/><w:szCs w:val="18"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault>
                    <w:pPrDefault><w:pPr><w:spacing w:after="60" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
                    <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
                    <w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:left w:w="80" w:type="dxa"/><w:bottom w:w="40" w:type="dxa"/><w:right w:w="80" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>
                    </w:styles>
                    """);
            put(zip, "word/document.xml", document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void flushFields(StringBuilder body, List<PlanDocument.Field> fields) {
        if (fields.isEmpty()) {
            return;
        }
        int[] widths = {500, 4300, TEXT_WIDTH - 4800};
        StringBuilder rows = new StringBuilder();
        for (PlanDocument.Field f : fields) {
            rows.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
                    .append(cell(f.number(), widths[0], true, null, 1))
                    .append(cell(f.label(), widths[1], true, null, 1))
                    .append(cell(f.value() == null || f.value().isBlank() ? "—" : f.value(), widths[2], false, null, 1))
                    .append("</w:tr>");
        }
        body.append(tableXml(widths, rows.toString())).append(paragraph("", false, 8, false));
        fields.clear();
    }

    private static String table(PlanDocument.Table t) {
        int total = t.weights().stream().mapToInt(Integer::intValue).sum();
        int[] widths = new int[t.weights().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = TEXT_WIDTH * t.weights().get(i) / total;
        }
        StringBuilder rows = new StringBuilder("<w:tr><w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>");
        for (int i = 0; i < t.columns().size(); i++) {
            rows.append(cell(t.columns().get(i), widths[i], true, HEADER_FILL, 1));
        }
        rows.append("</w:tr>");
        if (t.rows().isEmpty()) {
            rows.append("<w:tr>").append(cell("Шагов нет", TEXT_WIDTH, false, null, widths.length)).append("</w:tr>");
        }
        for (List<String> r : t.rows()) {
            rows.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>");
            if (r.size() == 1) {
                rows.append(cell(r.getFirst(), TEXT_WIDTH, true, SECTION_FILL, widths.length));
            } else {
                for (int i = 0; i < r.size(); i++) {
                    rows.append(cell(r.get(i), widths[i], false, null, 1));
                }
            }
            rows.append("</w:tr>");
        }
        return tableXml(widths, rows.toString()) + paragraph("", false, 8, false);
    }

    private static String tableXml(int[] widths, String rows) {
        StringBuilder grid = new StringBuilder("<w:tblGrid>");
        for (int w : widths) {
            grid.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        }
        grid.append("</w:tblGrid>");
        return "<w:tbl><w:tblPr><w:tblW w:w=\"" + TEXT_WIDTH + "\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>"
                + "<w:tblBorders>" + border("top") + border("left") + border("bottom") + border("right")
                + border("insideH") + border("insideV") + "</w:tblBorders></w:tblPr>" + grid + rows + "</w:tbl>";
    }

    private static String border(String side) {
        return "<w:" + side + " w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"9AA3B2\"/>";
    }

    private static String cell(String text, int width, boolean bold, String fill, int span) {
        StringBuilder sb = new StringBuilder("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>");
        if (span > 1) {
            sb.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
        }
        if (fill != null) {
            sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>");
        }
        sb.append("</w:tcPr>");
        String[] lines = (text == null ? "" : text).split("\n", -1);
        for (String line : lines) {
            sb.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>").append(run(line, bold, 0)).append("</w:p>");
        }
        return sb.append("</w:tc>").toString();
    }

    private static String paragraph(String text, boolean bold, int size, boolean spaceBefore) {
        return "<w:p><w:pPr>" + (spaceBefore ? "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/>" : "")
                + "</w:pPr>" + run(text, bold, size) + "</w:p>";
    }

    private static String run(String text, boolean bold, int size) {
        String rpr = (bold ? "<w:b/>" : "") + (size > 0 ? "<w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size + "\"/>" : "");
        return "<w:r>" + (rpr.isEmpty() ? "" : "<w:rPr>" + rpr + "</w:rPr>") + "<w:t xml:space=\"preserve\">"
                + PlanXlsx.xml(text) + "</w:t></w:r>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.strip().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
