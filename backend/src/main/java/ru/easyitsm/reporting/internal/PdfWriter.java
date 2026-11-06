package ru.easyitsm.reporting.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import ru.easyitsm.reporting.internal.ExportModel.Cell;
import ru.easyitsm.reporting.internal.ExportModel.Table;

/**
 * PDF: альбомный A4, шрифт Liberation Sans из сборки (кириллица, без обращения к системным шрифтам: продукт
 * работает в закрытом контуре). Таблицы переносятся на новые страницы с повтором шапки, длинный текст обрезается
 * многоточием. Значения показываются так же, как на экране: «2 ч 15 мин», «93,4 %».
 */
final class PdfWriter {

    private static final float MARGIN = 36;
    private static final float ROW = 15;
    private static final float FONT = 8.5f;
    private static final float PAD = 4;
    /** Цвета документа не зависят от темы интерфейса: печать всегда на белом. */
    private static final float[] HEADER_FILL = {0.94f, 0.95f, 0.96f};
    private static final float[] RULE = {0.85f, 0.87f, 0.9f};
    private static final float[] MUTED = {0.37f, 0.4f, 0.47f};

    private PdfWriter() {
    }

    static byte[] write(ExportModel model) {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = font(doc, "LiberationSans-Regular.ttf");
            PDFont bold = font(doc, "LiberationSans-Bold.ttf");
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(model.title());
            info.setCreator("Easy ITSM");
            new Layout(doc, regular, bold, model).render();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDFont font(PDDocument doc, String file) throws IOException {
        try (InputStream in = PdfWriter.class.getResourceAsStream("/reporting/fonts/" + file)) {
            if (in == null) {
                throw new IOException("Нет шрифта " + file);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    /** Раскладка документа сверху вниз с переносом страниц. */
    private static final class Layout {

        private final PDDocument doc;
        private final PDFont regular;
        private final PDFont bold;
        private final ExportModel model;
        private final PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        private PDPageContentStream cs;
        private float y;
        private int pageNo;
        /** Ширина текущей таблицы: заливка шапки и линии не шире колонок. */
        private float tableWidth;

        Layout(PDDocument doc, PDFont regular, PDFont bold, ExportModel model) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.model = model;
        }

        void render() throws IOException {
            newPage();
            text(bold, 16, MARGIN, y - 16, model.title(), null);
            y -= 26;
            for (String h : model.header()) {
                text(regular, 9, MARGIN, y - 9, fit(regular, 9, h, width()), MUTED);
                y -= 13;
            }
            y -= 6;
            for (Table t : model.tables()) {
                table(t);
            }
            finishPage();
        }

        private void table(Table t) throws IOException {
            float[] widths = widths(t);
            tableWidth = 0;
            for (float w : widths) {
                tableWidth += w;
            }
            if (y - (ROW * 3 + 20) < MARGIN) {
                finishPage();
                newPage();
            }
            y -= 8;
            if (model.tables().size() > 1) {
                text(bold, 11, MARGIN, y - 11, t.title(), null);
                y -= 18;
            }
            headerRow(t, widths);
            if (t.rows().isEmpty()) {
                text(regular, FONT, MARGIN + PAD, y - ROW + 4.5f, Labels.get(model.lang(), "file.empty"), MUTED);
                y -= ROW;
            }
            for (List<Cell> row : t.rows()) {
                if (y - ROW < MARGIN) {
                    finishPage();
                    newPage();
                    headerRow(t, widths);
                }
                float x = MARGIN;
                for (int i = 0; i < row.size(); i++) {
                    Cell c = row.get(i);
                    String v = fit(regular, FONT, ExportModel.display(c, model.lang(), true), widths[i] - 2 * PAD);
                    boolean right = !ReportViews.TEXT.equals(c.type()) && !ReportViews.DATE.equals(c.type());
                    float tx = right ? x + widths[i] - PAD - textWidth(regular, FONT, v) : x + PAD;
                    text(regular, FONT, tx, y - ROW + 4.5f, v, null);
                    x += widths[i];
                }
                rule(y - ROW);
                y -= ROW;
            }
            y -= 10;
        }

        private void headerRow(Table t, float[] widths) throws IOException {
            cs.setNonStrokingColor(HEADER_FILL[0], HEADER_FILL[1], HEADER_FILL[2]);
            cs.addRect(MARGIN, y - ROW, tableWidth, ROW);
            cs.fill();
            float x = MARGIN;
            for (int i = 0; i < t.columns().size(); i++) {
                String v = fit(bold, FONT, t.columns().get(i), widths[i] - 2 * PAD);
                String type = t.types().get(i);
                boolean right = !ReportViews.TEXT.equals(type) && !ReportViews.DATE.equals(type);
                float tx = right ? x + widths[i] - PAD - textWidth(bold, FONT, v) : x + PAD;
                text(bold, FONT, tx, y - ROW + 4.5f, v, null);
                x += widths[i];
            }
            y -= ROW;
        }

        /** Ширины колонок по содержимому, подогнанные под ширину страницы. */
        private float[] widths(Table t) throws IOException {
            int n = t.columns().size();
            float[] w = new float[n];
            for (int i = 0; i < n; i++) {
                w[i] = textWidth(bold, FONT, t.columns().get(i)) + 2 * PAD;
            }
            for (List<Cell> row : t.rows()) {
                for (int i = 0; i < n; i++) {
                    float cw = textWidth(regular, FONT, ExportModel.display(row.get(i), model.lang(), true)) + 2 * PAD;
                    w[i] = Math.max(w[i], Math.min(cw, 320));
                }
            }
            float total = 0;
            for (float v : w) {
                total += v;
            }
            float available = width();
            if (total > available) {
                // Сжимаем широкие колонки, узкие (числа) оставляем как есть.
                float narrow = 0;
                float wide = 0;
                for (float v : w) {
                    if (v <= 70) {
                        narrow += v;
                    } else {
                        wide += v;
                    }
                }
                float k = Math.max(0.1f, (available - narrow) / Math.max(1, wide));
                for (int i = 0; i < n; i++) {
                    if (w[i] > 70) {
                        w[i] *= k;
                    }
                }
            } else if (n <= 2) {
                w[0] = Math.max(w[0], Math.min(available * 0.5f, 360));
            }
            return w;
        }

        private float width() {
            return size.getWidth() - 2 * MARGIN;
        }

        private void rule(float at) throws IOException {
            cs.setStrokingColor(RULE[0], RULE[1], RULE[2]);
            cs.setLineWidth(0.5f);
            cs.moveTo(MARGIN, at);
            cs.lineTo(MARGIN + tableWidth, at);
            cs.stroke();
        }

        private void text(PDFont font, float sz, float x, float baseline, String v, float[] color) throws IOException {
            if (v == null || v.isEmpty()) {
                return;
            }
            if (color == null) {
                cs.setNonStrokingColor(0.08f, 0.1f, 0.13f);
            } else {
                cs.setNonStrokingColor(color[0], color[1], color[2]);
            }
            cs.beginText();
            cs.setFont(font, sz);
            cs.newLineAtOffset(x, baseline);
            cs.showText(v);
            cs.endText();
        }

        private void newPage() throws IOException {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = size.getHeight() - MARGIN;
            pageNo++;
        }

        private void finishPage() throws IOException {
            String label = String.valueOf(pageNo);
            text(regular, 8, size.getWidth() - MARGIN - textWidth(regular, 8, label), MARGIN / 2, label, MUTED);
            cs.close();
        }
    }

    /** Текст, который поместится в ширину; лишнее заменяется многоточием. Неподдерживаемые символы — «?». */
    static String fit(PDFont font, float size, String v, float max) throws IOException {
        String s = printable(font, v);
        if (textWidth(font, size, s) <= max) {
            return s;
        }
        String ellipsis = "…";
        // Начинаем с оценки по пропорции, чтобы не мерить строку посимвольно с конца.
        int end = Math.min(s.length(), (int) (s.length() * max / Math.max(1f, textWidth(font, size, s))) + 1);
        while (end > 0 && textWidth(font, size, s.substring(0, end) + ellipsis) > max) {
            end--;
        }
        return end == 0 ? "" : s.substring(0, end).stripTrailing() + ellipsis;
    }

    static float textWidth(PDFont font, float size, String v) throws IOException {
        return font.getStringWidth(printable(font, v)) / 1000f * size;
    }

    /** Заменяет символы, которых нет в шрифте (эмодзи, CJK), и управляющие символы. */
    private static String printable(PDFont font, String v) {
        if (v == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(v.length());
        List<Integer> cps = new ArrayList<>();
        v.codePoints().forEach(cps::add);
        for (int cp : cps) {
            if (Character.isISOControl(cp)) {
                out.append(' ');
                continue;
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        }
        return out.toString();
    }
}
