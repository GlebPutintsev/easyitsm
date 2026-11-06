package ru.easyitsm.mail.internal;

import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Текст входящего письма для ленты заявки (поток 6.4, шаг 2): HTML превращается в обычный текст —
 * скрипты, стили, изображения и разметка отбрасываются, ссылки остаются адресом. Лента хранит Markdown без HTML,
 * поэтому никакая часть HTML из письма в заявку не попадает. Цитата предыдущей переписки отрезается.
 */
final class MailText {

    /** Строка-разделитель в наших письмах: всё ниже неё в ответе — цитата. */
    static final String REPLY_MARKER = "##- Ответьте выше этой строки / Reply above this line -##";

    private static final Pattern[] QUOTE_HEADERS = {
            Pattern.compile("^-{2,}\\s*(Original Message|Исходное сообщение|Пересылаемое сообщение|Forwarded message)\\s*-{2,}",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(On|В|Le|Am)\\s.{4,200}(wrote|написал|написала|писал|a écrit|schrieb)\\s*(\\(а\\))?:\\s*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}.{0,60}(wrote|написал|пишет).{0,40}:\\s*$",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(From|От|Отправитель):\\s.+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^_{10,}\\s*$"),
    };

    private MailText() {
    }

    static String htmlToText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, head, title, img, object, embed, iframe, svg, noscript").remove();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    sb.append(t.text());
                } else if (node instanceof Element e) {
                    switch (e.normalName()) {
                        case "br" -> sb.append('\n');
                        case "li" -> sb.append("\n- ");
                        case "p", "div", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "table", "ul", "ol",
                             "blockquote", "pre", "hr" -> sb.append('\n');
                        case "td", "th" -> sb.append(' ');
                        default -> {
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e) {
                    switch (e.normalName()) {
                        case "p", "h1", "h2", "h3", "h4", "h5", "h6", "table", "blockquote", "pre" -> sb.append('\n');
                        case "a" -> {
                            String href = e.attr("href");
                            if (href.startsWith("http://") || href.startsWith("https://")) {
                                if (!e.text().strip().equals(href)) {
                                    sb.append(" (").append(href).append(')');
                                }
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }, doc.body() == null ? doc : doc.body());
        // Если в письме цитата оформлена blockquote с атрибутом type=cite, она тоже останется текстом:
        // её отрежет stripQuoted по строке-шапке цитаты.
        return sb.toString();
    }

    /** Убирает управляющие символы, неразрывные пробелы и лишние пустые строки. */
    static String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        s = s.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }

    /** Отрезает цитату: по нашему разделителю, шапке цитаты почтового клиента или блоку строк с «>». */
    static String stripQuoted(String text) {
        int marker = text.indexOf(REPLY_MARKER);
        if (marker >= 0) {
            text = text.substring(0, marker);
            // Строка шапки клиента, которая стоит прямо над разделителем, тоже часть цитаты.
        }
        String[] lines = text.split("\n", -1);
        int cut = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (i > 0 && isQuoteHeader(line)) {
                cut = i;
                break;
            }
            if (line.startsWith(">") && i > 0 && restIsQuoted(lines, i)) {
                cut = i;
                // Шапка «… wrote:» строкой выше — тоже часть цитаты.
                if (i > 0 && lines[i - 1].strip().endsWith(":")) {
                    cut = i - 1;
                }
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cut; i++) {
            sb.append(lines[i]).append('\n');
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? text.strip() : result;
    }

    private static boolean isQuoteHeader(String line) {
        for (Pattern p : QUOTE_HEADERS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean restIsQuoted(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            String l = lines[i].strip();
            if (!l.isEmpty() && !l.startsWith(">")) {
                return false;
            }
        }
        return true;
    }
}
