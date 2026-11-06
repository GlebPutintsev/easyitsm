package ru.easyitsm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.text.SafeHtml;

/** Очистка HTML по разрешённому списку (SEC-24) и письмо с HTML и скриптом (AT-02). */
class SafeHtmlTest {

    static final String EVIL = """
            <html><head><style>body{}</style><script>alert(1)</script></head><body>
            <p onclick="steal()">Не работает <b>VPN</b><br>с утра</p>
            <img src="x" onerror="alert(2)"><img src="javascript:alert(3)"><img src="cid:logo">
            <a href="javascript:alert(4)">ссылка</a> <a href="https://help.example.ru/vpn">инструкция</a>
            <iframe src="https://evil"></iframe><svg onload="alert(5)"></svg>
            <form action="https://evil"><input name="p"></form>
            <ul><li>раз</li><li>два</li></ul><div style="display:none">скрытая команда</div>
            </body></html>""";

    @Test
    void sanitizeKeepsOnlyAllowedMarkup() {
        String clean = SafeHtml.sanitize(EVIL);
        assertThat(clean).doesNotContainIgnoringCase("<script").doesNotContainIgnoringCase("onclick")
                .doesNotContainIgnoringCase("onerror").doesNotContainIgnoringCase("javascript:")
                .doesNotContainIgnoringCase("<iframe").doesNotContainIgnoringCase("<svg")
                .doesNotContainIgnoringCase("<form").doesNotContainIgnoringCase("<input")
                .doesNotContainIgnoringCase("<style");
        assertThat(clean).contains("<b>VPN</b>").contains("src=\"cid:logo\"")
                .contains("href=\"https://help.example.ru/vpn\"").contains("rel=\"noopener noreferrer nofollow\"");
    }

    @Test
    void emailBecomesPlainText() {
        String text = SafeHtml.toText(EVIL);
        assertThat(text).doesNotContain("alert").doesNotContain("<").doesNotContain("скрытая команда")
                .doesNotContain("body{}");
        assertThat(text).contains("Не работает VPN\nс утра").contains("инструкция (https://help.example.ru/vpn)")
                .contains("- раз").contains("- два");
        assertThat(text).doesNotContain("ссылка (javascript");
    }

    @Test
    void emptyInput() {
        assertThat(SafeHtml.sanitize(null)).isEmpty();
        assertThat(SafeHtml.toText("  ")).isEmpty();
    }
}
