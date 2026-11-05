package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import ru.easyitsm.tickets.api.SecretFinding;
import ru.easyitsm.tickets.api.SecretScanner;

/** Шаблоны обнаружения секретов (TCK-17). Все значения поддельные. */
class RegexSecretScannerTest {

    final SecretScanner scanner = create();

    static SecretScanner create() {
        try {
            Class<?> c = Class.forName("ru.easyitsm.tickets.internal.RegexSecretScanner");
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return (SecretScanner) ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    String kinds(String text) {
        return String.join(",", scanner.scan(text).stream().map(SecretFinding::kind).toList());
    }

    @Test
    void findsTypicalSecrets() {
        assertThat(kinds("пароль: Qwerty")).isEqualTo("password");
        assertThat(kinds("Password=abcd1234")).isEqualTo("password");
        assertThat(kinds("мой пароль Summer2026")).isEqualTo("password");
        assertThat(kinds("ключ AKIA" + "ABCDEFGHIJKLMNOP")).isEqualTo("aws_key");
        assertThat(kinds("token ghp_" + "a".repeat(36))).isEqualTo("github_token");
        assertThat(kinds("jdbc postgres://admin:S3cr3tPwd@db.local:5432/app")).isEqualTo("connection_string");
        assertThat(kinds("-----BEGIN RSA PRIVATE KEY-----\nMIIfake\n-----END RSA PRIVATE KEY-----")).isEqualTo("private_key");
        assertThat(kinds("api_key = abcdefghijklmnop1234")).isEqualTo("token");
        assertThat(kinds("карта 4539 1488 0343 6467")).isEqualTo("card_number");
    }

    @Test
    void ignoresOrdinaryText() {
        assertThat(kinds("Пароль сменён, спасибо")).isEmpty();
        assertThat(kinds("Сбросьте пароль от почты")).isEmpty();
        assertThat(kinds("Номер заказа 1234 5678 9012 3456")).isEmpty(); // не проходит Луна
        assertThat(kinds("Счётчик 1111 1111 1111 1111")).isEmpty(); // слишком однообразно
        assertThat(kinds("Паспорт 4510 123456")).isEmpty();
        assertThat(kinds("Заявка INC-000123 по VPN")).isEmpty();
    }

    @Test
    void masksWithoutRevealingValue() {
        String masked = scanner.mask("Логин ivanov, пароль: Qwerty123, карта 4539148803436467.");
        assertThat(masked).isEqualTo("Логин ivanov, пароль: ********, карта **** **** **** 6467.");
        assertThat(scanner.mask("token ghp_" + "b".repeat(40))).isEqualTo("token ghp_********");
    }
}
