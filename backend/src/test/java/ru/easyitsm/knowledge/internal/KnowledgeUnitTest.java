package ru.easyitsm.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Чистые функции модуля: поиск секретов, фрагменты, поисковый запрос, роли читателя. */
class KnowledgeUnitTest {

    @Test
    void secretScannerFindsTypicalSecrets() {
        var findings = SecretScanner.scan("Заголовок", """
                Подключение к базе
                password = S3cr3t!pass
                postgres://app:hunter22@db:5432/main
                AKIAIOSFODNN7EXAMPLE
                -----BEGIN RSA PRIVATE KEY-----
                api_key: abcdef0123456789abcdef""");
        assertThat(findings).extracting(KnowledgeDtos.SecretFinding::kind)
                .contains("password", "connection_string", "aws_key", "private_key", "api_key");
        assertThat(findings).filteredOn(f -> f.kind().equals("password"))
                .extracting(KnowledgeDtos.SecretFinding::line).containsExactly(2);
        assertThat(SecretScanner.scan("token: abcdef0123456789abcdef", ""))
                .extracting(KnowledgeDtos.SecretFinding::line).containsExactly(0);
    }

    @Test
    void secretScannerIgnoresPlaceholdersAndInstructions() {
        assertThat(SecretScanner.scan("Как сменить пароль", """
                Введите пароль: <ваш пароль>
                Пароль: ******
                Смените пароль в личном кабинете""")).isEmpty();
    }

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
