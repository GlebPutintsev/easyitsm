package ru.easyitsm.changes.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * План работ в PDF: альбомный A4, шрифт Liberation Sans из сборки (кириллица, закрытый контур без системных
 * шрифтов). Текст в ячейках переносится по словам, шапка таблицы повторяется на новой странице, номер страницы
 * внизу. Печать всегда на белом, цвета не зависят от темы интерфейса.
 */
final class PlanPdf {

    private static final float MARGIN = 28;
    private static final float FONT = 7.5f;
    private static final float LEADING = 9.2f;
    private static final float PAD = 3;
    private static final float[] HEADER_FILL = {0.91f, 0.925f, 0.953f};
    private static final float[] SECTION_FILL = {0.957f, 0.933f, 0.875f};
    private static final float[] RULE = {0.6f, 0.64f, 0.7f};
    private static final float[] MUTED = {0.37f, 0.4f, 0.47f};

    private PlanPdf() {
    }

    static byte[] write(PlanDocument.Doc doc) {
        try (PDDocument pdf = new PDDocument()) {
            PDFont regular = font(pdf, "LiberationSans-Regular.ttf");
            PDFont bold = font(pdf, "LiberationSans-Bold.ttf");
            pdf.getDocumentInformation().setTitle(doc.title());
            pdf.getDocumentInformation().setCreator("Easy ITSM");
            new Layout(pdf, regular, bold).render(doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDFont font(PDDocument doc, String file) throws IOException {
        // Шрифты лежат в сборке рядом с отчётами: один набор на продукт.
        try (InputStream in = PlanPdf.class.getResourceAsStream("/reporting/fonts/" + file)) {
            if (in == null) {
                throw new IOException("Нет шрифта " + file);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    private static final class Layout {

        private final PDDocument doc;
        private final PDFont regular;
        private final PDFont bold;
        private final PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        private final Map<PDFont, Map<Integer, Boolean>> glyphs = new HashMap<>();
        private PDPageContentStream cs;
        private float y;
        private int page;

        Layout(PDDocument doc, PDFont regular, PDFont bold) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
        }

        float width() {
            return size.getWidth() - 2 * MARGIN;
        }

        void render(PlanDocument.Doc d) throws IOException {
            newPage();
            for (String line : wrap(d.title(), bold, 13, width())) {
                text(bold, 13, MARGIN, y - 13, line, null);
                y -= 16;
            }
            y -= 4;
            boolean first = true;
            for (PlanDocument.Sheet sheet : d.sheets()) {
                if (!first) {
                    finishPage();
                    newPage();
                }
                first = false;
                List<PlanDocument.Field> fields = new ArrayList<>();
                for (PlanDocument.Block b : sheet.blocks()) {
                    if (b instanceof PlanDocument.Field f) {
                        fields.add(f);
                        continue;
                    }
                    flush(fields);
                    switch (b) {
                        case PlanDocument.Heading h -> heading((h.number().isEmpty() ? "" : h.number() + ". ") + h.text());
                        case PlanDocument.Table t -> table(t);
                        default -> {
                        }
                    }
                }
                flush(fields);
            }
            finishPage();
        }

        private void flush(List<PlanDocument.Field> fields) throws IOException {
            if (fields.isEmpty()) {
                return;
            }
            float[] widths = {22, 210, width() - 232};
            for (PlanDocument.Field f : fields) {
                row(widths, List.of(f.number(), f.label(), f.value() == null || f.value().isBlank() ? "—" : f.value()),
                        new boolean[]{true, true, false}, null, null);
            }
            y -= 6;
            fields.clear();
        }

        private void heading(String text) throws IOException {
            if (y - 40 < MARGIN) {
                finishPage();
                newPage();
            }
            y -= 4;
            text(bold, 10, MARGIN, y - 10, fit(text, bold, 10, width()), null);
            y -= 15;
        }

        private void table(PlanDocument.Table t) throws IOException {
            int total = t.weights().stream().mapToInt(Integer::intValue).sum();
            float[] widths = new float[t.weights().size()];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = width() * t.weights().get(i) / total;
            }
            boolean[] allBold = new boolean[widths.length];
            java.util.Arrays.fill(allBold, true);
            boolean[] plain = new boolean[widths.length];
            row(widths, t.columns(), allBold, HEADER_FILL, null);
            if (t.rows().isEmpty()) {
                row(new float[]{width()}, List.of("Шагов нет"), new boolean[]{false}, null, null);
            }
            for (List<String> r : t.rows()) {
                if (r.size() == 1) {
                    row(new float[]{width()}, r, new boolean[]{true}, SECTION_FILL, null);
                } else {
                    row(widths, r, plain, null, () -> row(widths, t.columns(), allBold, HEADER_FILL, null));
                }
            }
            y -= 8;
        }

        private interface Repeat {
            void run() throws IOException;
        }

        /** Строка таблицы с переносом текста; не помещается — новая страница и повтор шапки. */
        private void row(float[] widths, List<String> cells, boolean[] boldCells, float[] fill, Repeat header)
                throws IOException {
            List<List<String>> lines = new ArrayList<>();
            int max = 1;
            for (int i = 0; i < cells.size(); i++) {
                PDFont f = boldCells[i] ? bold : regular;
                List<String> l = wrap(cells.get(i), f, FONT, widths[i] - 2 * PAD);
                lines.add(l);
                max = Math.max(max, l.size());
            }
            float available = size.getHeight() - 2 * MARGIN - 30;
            int maxLines = (int) ((available - 2 * PAD) / LEADING);
            if (max > maxLines) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).size() > maxLines) {
                        List<String> cut = new ArrayList<>(lines.get(i).subList(0, maxLines - 1));
                        cut.add("…");
                        lines.set(i, cut);
                    }
                }
                max = maxLines;
            }
            float h = max * LEADING + 2 * PAD;
            if (y - h < MARGIN + 14) {
                finishPage();
                newPage();
                if (header != null) {
                    header.run();
                }
            }
            float x = MARGIN;
            for (int i = 0; i < cells.size(); i++) {
                if (fill != null) {
                    cs.setNonStrokingColor(fill[0], fill[1], fill[2]);
                    cs.addRect(x, y - h, widths[i], h);
                    cs.fill();
                }
                cs.setStrokingColor(RULE[0], RULE[1], RULE[2]);
                cs.setLineWidth(0.4f);
                cs.addRect(x, y - h, widths[i], h);
                cs.stroke();
                PDFont f = boldCells[i] ? bold : regular;
                float ty = y - PAD - FONT;
                for (String line : lines.get(i)) {
                    text(f, FONT, x + PAD, ty, line, null);
                    ty -= LEADING;
                }
                x += widths[i];
            }
            y -= h;
        }

        private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
            List<String> out = new ArrayList<>();
            String safe = sanitize(text == null ? "" : text, font);
            for (String para : safe.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : para.split(" ", -1)) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (width(candidate, font, fontSize) <= maxWidth) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    String rest = word;
                    while (width(rest, font, fontSize) > maxWidth && rest.length() > 1) {
                        int cut = rest.length() - 1;
                        while (cut > 1 && width(rest.substring(0, cut), font, fontSize) > maxWidth) {
                            cut--;
                        }
                        out.add(rest.substring(0, cut));
                        rest = rest.substring(cut);
                    }
                    line.append(rest);
                }
                out.add(line.toString());
            }
            return out.isEmpty() ? List.of("") : out;
        }

        private float width(String s, PDFont font, float fontSize) throws IOException {
            return font.getStringWidth(s) / 1000 * fontSize;
        }

        private String fit(String s, PDFont font, float fontSize, float maxWidth) throws IOException {
            String v = sanitize(s, font);
            if (width(v, font, fontSize) <= maxWidth) {
                return v;
            }
            while (v.length() > 1 && width(v + "…", font, fontSize) > maxWidth) {
                v = v.substring(0, v.length() - 1);
            }
            return v + "…";
        }

        /** Символы без глифа в шрифте (эмодзи, редкие знаки) заменяются «?», иначе PDFBox бросает исключение. */
        private String sanitize(String s, PDFont font) {
            Map<Integer, Boolean> known = glyphs.computeIfAbsent(font, f -> new HashMap<>());
            StringBuilder b = new StringBuilder(s.length());
            s.codePoints().forEach(cp -> {
                if (cp == '\n') {
                    b.append('\n');
                    return;
                }
                if (cp == '\t' || cp == '\r') {
                    b.append(' ');
                    return;
                }
                boolean ok = known.computeIfAbsent(cp, c -> {
                    try {
                        font.encode(new String(Character.toChars(c)));
                        return true;
                    } catch (IOException | IllegalArgumentException e) {
                        return false;
                    }
                });
                b.append(ok ? new String(Character.toChars(cp)) : "?");
            });
            return b.toString();
        }

        private void text(PDFont font, float fontSize, float x, float baseline, String s, float[] color)
                throws IOException {
            if (s.isEmpty()) {
                return;
            }
            cs.beginText();
            cs.setFont(font, fontSize);
            if (color != null) {
                cs.setNonStrokingColor(color[0], color[1], color[2]);
            } else {
                cs.setNonStrokingColor(0.08f, 0.13f, 0.24f);
            }
            cs.newLineAtOffset(x, baseline);
            cs.showText(s);
            cs.endText();
        }

        private void newPage() throws IOException {
            PDPage p = new PDPage(size);
            doc.addPage(p);
            cs = new PDPageContentStream(doc, p);
            page++;
            y = size.getHeight() - MARGIN;
        }

        private void finishPage() throws IOException {
            text(regular, 7, size.getWidth() - MARGIN - 40, MARGIN - 14, "Стр. " + page, MUTED);
            cs.close();
        }
    }
}
