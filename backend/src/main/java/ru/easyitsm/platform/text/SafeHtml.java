package ru.easyitsm.platform.text;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * HTML из внешних источников: письма, вставка из буфера, интеграции (SEC-24, AT-02). Заявки и статьи хранят
 * Markdown без HTML, поэтому входящее письмо превращается в текст ({@link #toText}); где HTML нужен
 * (предпросмотр письма), он очищается по разрешённому списку ({@link #sanitize}).
 * <p>
 * Скрипты, стили, обработчики событий, {@code iframe}, {@code object}, формы и ссылки {@code javascript:}
 * удаляются всегда. Картинки — только {@code cid:} и {@code https:}, ссылки — {@code http(s)} и {@code mailto}
 * с {@code rel="noopener noreferrer nofollow"}.
 */
public final class SafeHtml {

    private static final Safelist ALLOWED = Safelist.relaxed()
            .removeTags("img")
            .addTags("img", "hr", "del", "ins", "s")
            .addAttributes("img", "alt", "src", "width", "height")
            .addProtocols("img", "src", "cid", "https")
            .removeProtocols("a", "href", "ftp")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow");

    private SafeHtml() {
    }

    /** Очищенный HTML: только разрешённые теги, атрибуты и схемы ссылок. */
    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", ALLOWED, out);
    }

    /**
     * Текст для заявки из HTML письма: абзацы, переводы строк и пункты списков сохраняются, ссылки
     * становятся «текст (адрес)», скрипты, стили и скрытое содержимое отбрасываются.
     */
    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, template, head, iframe, object, embed, svg, math").remove();
        doc.select("[hidden], [style*=display:none], [style*=display: none]").remove();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    sb.append(text.text());
                } else if (node instanceof Element el) {
                    switch (el.normalName()) {
                        case "br" -> sb.append('\n');
                        case "li" -> sb.append("\n- ");
                        case "p", "div", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table" ->
                                newline(sb);
                        default -> { }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el) {
                    switch (el.normalName()) {
                        case "a" -> {
                            String href = el.attr("href");
                            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                                if (!el.text().equals(href) && !href.equals("mailto:" + el.text())) {
                                    sb.append(" (").append(href).append(')');
                                }
                            }
                        }
                        case "p", "div", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table",
                             "ul", "ol" -> newline(sb);
                        case "td", "th" -> sb.append('\t');
                        default -> { }
                    }
                }
            }
        }, doc.body());
        return sb.toString().replace(' ', ' ')
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static void newline(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }
}
