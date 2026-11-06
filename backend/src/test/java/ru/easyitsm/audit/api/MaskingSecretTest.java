package ru.easyitsm.audit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Маскирование (SEC-13). Значения ниже — поддельные секреты для проверки шаблонов. */
class MaskingSecretTest {

    @Test
    void masksSecretsInText() {
        assertThat(Masking.text("Пароль: Qwerty123! от почты")).isEqualTo("Пароль: *** от почты");
        assertThat(Masking.text("password=hunter2&x=1")).isEqualTo("password=***&x=1");
        assertThat(Masking.text("мой пароль Zx9-kd!pq сменить")).isEqualTo("мой пароль *** сменить");
        assertThat(Masking.text("Authorization: Bearer abcdefghijklmnop.qrstuv")).doesNotContain("abcdefghijklmnop");
        assertThat(Masking.text("ключ AKIAIOSFODNN7EXAMPLE в конфиге")).isEqualTo("ключ *** в конфиге");
        assertThat(Masking.text("ghp_" + "a".repeat(36))).isEqualTo("***");
        assertThat(Masking.text("jdbc:postgresql://admin:S3cr3t@db:5432/x")).isEqualTo("jdbc:postgresql://***@db:5432/x");
        assertThat(Masking.text("https://x.ru/cb?code=abc123&state=1")).isEqualTo("https://x.ru/cb?code=***&state=1");
        assertThat(Masking.text("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlMTIz")).isEqualTo("***");
        assertThat(Masking.text("-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----"))
                .isEqualTo("***");
    }

    @Test
    void masksCardNumbersByLuhnOnly() {
        assertThat(Masking.text("карта 4111 1111 1111 1111 оплачена")).isEqualTo("карта ****1111 оплачена");
        // Не карта (не проходит Луна) — номер заявки или телефон остаются
        assertThat(Masking.text("номер 1234567890123")).isEqualTo("номер 1234567890123");
    }

    @Test
    void keepsOrdinaryText() {
        String text = "Не работает принтер на 3 этаже, токен доступа истёк вчера";
        assertThat(Masking.text(text)).isEqualTo(text);
        assertThat(Masking.text("pass the ticket to L2")).isEqualTo("pass the ticket to L2");
    }

    @Test
    void masksByKey() {
        Map<String, Object> d = Masking.details(Map.of(
                "password", "x", "resetUrl", "https://a", "api_key", "k", "otp", "123456", "mapping", "ok",
                "nested", Map.of("client_secret", "s", "name", "n"), "list", List.of("token=abc", "ok"), "rows", 5));
        assertThat(d).containsEntry("password", "***").containsEntry("resetUrl", "***")
                .containsEntry("api_key", "***").containsEntry("otp", "***").containsEntry("mapping", "ok")
                .containsEntry("rows", 5);
        assertThat(d.get("nested")).isEqualTo(Map.of("client_secret", "***", "name", "n"));
        assertThat(d.get("list")).isEqualTo(List.of("token=***", "ok"));
        assertThat(Masking.isSensitiveKey("shipping")).isFalse();
    }

    @Test
    void personalData() {
        assertThat(Masking.email("ivan.petrov@corp.ru")).isEqualTo("i***@corp.ru");
        assertThat(Masking.phone("+7 (912) 345-67-89")).isEqualTo("***89");
        assertThat(Masking.forLog("письмо от ivan@corp.ru, пароль: 123")).isEqualTo("письмо от i***@corp.ru, пароль: ***");
    }

    @Test
    void longValuesAreTruncated() {
        String value = (String) Masking.value("comment", "x".repeat(5000));
        assertThat(value).hasSize(Masking.MAX_VALUE + 1);
    }
}
