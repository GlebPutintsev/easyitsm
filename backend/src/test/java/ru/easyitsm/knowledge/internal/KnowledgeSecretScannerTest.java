package ru.easyitsm.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Поиск секретов в статье перед отправкой на проверку (10.12). Значения ниже поддельные: это тестовые данные
 * детектора, gitleaks пропускает файлы *Secret*Test.java по соглашению из .gitleaks.toml.
 */
class KnowledgeSecretScannerTest {

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
}
