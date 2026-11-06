package ru.easyitsm.notifications.internal;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * Подстановка переменных в шаблон: {@code {{имя}}}, {@code {{#имя}}…{{/имя}}} (если не пусто),
 * {@code {{^имя}}…{{/имя}}} (если пусто). Логики и вызовов нет — шаблон администратора не может выполнить код
 * и не видит ничего, кроме переданной модели.
 */
final class TemplateRenderer {

    private static final Pattern SECTION = Pattern.compile("\\{\\{([#^])\\s*([\\w.]+)\\s*}}(.*?)\\{\\{/\\s*\\2\\s*}}",
            Pattern.DOTALL);
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final Pattern URL = Pattern.compile("(https?://[^\\s<>\"]+)");

    private TemplateRenderer() {
    }

    static String render(String template, Map<String, String> model) {
        String s = template;
        // Вложенные блоки раскрываются снаружи внутрь, пока есть что раскрывать.
        for (int i = 0; i < 10; i++) {
            Matcher m = SECTION.matcher(s);
            if (!m.find()) {
                break;
            }
            m.reset();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String value = model.getOrDefault(m.group(2), "");
                boolean present = !value.isBlank();
                boolean show = m.group(1).equals("#") == present;
                m.appendReplacement(sb, Matcher.quoteReplacement(show ? m.group(3) : ""));
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        Matcher v = VAR.matcher(s);
        StringBuilder out = new StringBuilder();
        while (v.find()) {
            v.appendReplacement(out, Matcher.quoteReplacement(model.getOrDefault(v.group(1), "")));
        }
        v.appendTail(out);
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /** Тема: одна строка без переводов (защита от подстановки заголовков). */
    static String subject(String template, Map<String, String> model) {
        String s = render(template, model).replaceAll("[\\r\\n\\t]+", " ").strip();
        return s.length() > 300 ? s.substring(0, 299) + "…" : s;
    }

    /** HTML-версия письма из готового текста: экранирование, абзацы, ссылки. Стили встроенные — так их видят клиенты. */
    static String html(String text, String product) {
        StringBuilder body = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String escaped = HtmlUtils.htmlEscape(para.strip()).replace("\n", "<br>");
            escaped = URL.matcher(escaped).replaceAll(
                    "<a href=\"$1\" style=\"color:#1d4ed8;text-decoration:underline\">$1</a>");
            body.append("<p style=\"margin:0 0 14px\">").append(escaped).append("</p>");
        }
        return "<!doctype html><html><body style=\"margin:0;padding:0;background:#f4f5f7\">"
                + "<div style=\"max-width:600px;margin:0 auto;padding:24px 16px;font-family:Manrope,Segoe UI,Arial,"
                + "sans-serif;font-size:14px;line-height:1.55;color:#1f2430\">"
                + "<div style=\"background:#ffffff;border:1px solid #e3e5ea;border-radius:8px;padding:24px\">"
                + body
                + "</div><div style=\"color:#8a8f98;font-size:12px;margin-top:12px\">"
                + HtmlUtils.htmlEscape(product) + "</div></div></body></html>";
    }
}
