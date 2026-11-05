package ru.easyitsm.knowledge.internal;

import java.util.regex.Pattern;

/** Короткий фрагмент текста статьи без разметки Markdown для списков и подсказок. */
final class Snippets {

    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+(.*?)[.:!?]?\\s*$");
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?(```|$)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern MARKUP = Pattern.compile("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+|[*_`~|]|^\\s*-{3,}\\s*$|:?-{3,}:?");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Snippets() {
    }

    static String plain(String markdown, int maxChars) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String s = CODE_BLOCK.matcher(markdown).replaceAll(" ");
        s = HEADING.matcher(s).replaceAll("$1.");
        s = IMAGE.matcher(s).replaceAll("$1");
        s = LINK.matcher(s).replaceAll("$1");
        s = MARKUP.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        if (s.length() <= maxChars) {
            return s;
        }
        int cut = s.lastIndexOf(' ', maxChars);
        return s.substring(0, cut > maxChars / 2 ? cut : maxChars) + "…";
    }
}
