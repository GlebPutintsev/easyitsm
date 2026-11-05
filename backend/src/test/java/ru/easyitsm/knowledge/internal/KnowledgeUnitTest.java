package ru.easyitsm.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Чистые функции модуля: фрагменты, поисковый запрос, роли читателя. Поиск секретов — в KnowledgeSecretScannerTest. */
class KnowledgeUnitTest {

    @Test
    void snippetStripsMarkdown() {
        String s = Snippets.plain("""
                ## Шаги
                1. Откройте **Настройки**
                ```
                rm -rf /
                ```
                | a | b |
                |---|---|
                См. [инструкцию](https://x) и ![схема](img.png)""", 200);
        assertThat(s).isEqualTo("Шаги. Откройте Настройки a b См. инструкцию и схема");
        assertThat(Snippets.plain("слово ".repeat(100), 30)).endsWith("…").hasSizeLessThanOrEqualTo(31);
    }

    @Test
    void tsQueryKeepsOnlyWords() {
        assertThat(ArticleSearch.tsQuery("Принтер: не печатает!", " & ", true)).isEqualTo("принтер:* & не:* & печатает:*");
        assertThat(ArticleSearch.tsQuery("a' | b & !c", " | ", false)).isNull();
        assertThat(ArticleSearch.tsQuery("VPN 2", " | ", false)).isEqualTo("vpn | 2");
        assertThat(ArticleSearch.escapeLike("50%_\\")).isEqualTo("50\\%\\_\\\\");
    }

    @Test
    void readerRolesAndAudiences() {
        UUID me = UUID.randomUUID();
        Reader requester = Reader.of(me, Set.of("REQ"), Set.of());
        assertThat(requester.editor()).isFalse();
        assertThat(requester.employee()).isTrue();
        assertThat(requester.staff()).isFalse();

        Reader external = Reader.of(me, Set.of("REQ", "EXT"), Set.of());
        assertThat(external.employee()).isFalse();

        Reader agent = Reader.of(me, Set.of("AGT"), Set.of());
        assertThat(agent.editor()).isTrue();
        assertThat(agent.staff()).isTrue();
        assertThat(agent.manager()).isFalse();

        Reader knw = Reader.of(me, Set.of("KNW"), Set.of());
        assertThat(knw.seesEverything()).isTrue();
    }
}
