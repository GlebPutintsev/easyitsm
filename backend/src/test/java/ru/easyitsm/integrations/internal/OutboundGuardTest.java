package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Защита исходящих запросов от SSRF: разрешённые адреса, служебные и внутренние сети (раздел 8.5 ТЗ). */
class OutboundGuardTest {

    /** Имена узлов теста и их адреса: без настоящего DNS. */
    static final Map<String, String> DNS = Map.of(
            "hooks.example.ru", "203.0.113.10",
            "api.partner.ru", "198.51.100.7",
            "evil.partner.ru", "10.0.0.5",
            "jira.corp.local", "10.1.2.3",
            "meta.partner.ru", "169.254.169.254",
            "local.partner.ru", "127.0.0.1");

    static OutboundGuard guard(boolean loopback, String... allowed) {
        return new OutboundGuard(List.of(allowed), loopback, host -> {
            String ip = DNS.get(host);
            if (ip == null) {
                if (OutboundGuard.isLiteral(host)) {
                    return new InetAddress[] {InetAddress.getByName(host)};
                }
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(ip)};
        });
    }

    static void denied(OutboundGuard g, String url, String reason) {
        assertThatThrownBy(() -> g.check(URI.create(url))).isInstanceOf(OutboundGuard.Denied.class)
                .hasMessageContaining(reason);
    }

    @Test
    void emptyListDeniesEverything() {
        denied(guard(false), "https://hooks.example.ru/x", "пуст");
    }

    @Test
    void exactHostAndWildcardAreAllowedOthersAreNot() {
        OutboundGuard g = guard(false, "hooks.example.ru", "*.partner.ru");
        assertThat(g.check(URI.create("https://hooks.example.ru/in"))).hasSize(1);
        assertThat(g.check(URI.create("https://api.partner.ru/in"))).hasSize(1);
        denied(g, "https://partner.ru.attacker.com/in", "не входит");
        denied(g, "https://example.ru/in", "не входит");
        denied(g, "ftp://hooks.example.ru/in", "http");
        denied(g, "https://user:pass@hooks.example.ru/in", "логин");
        denied(g, "https://unknown.partner.ru/in", "не найден");
    }

    @Test
    void wildcardDoesNotLeadIntoInternalNetworkOrMetadata() {
        OutboundGuard g = guard(false, "*.partner.ru");
        // Имя под маской, которое указывает во внутреннюю сеть, не пропускается.
        denied(g, "https://evil.partner.ru/in", "внутренний адрес");
        // Метаданные облака и сам сервер запрещены всегда.
        denied(g, "https://meta.partner.ru/latest", "служебный адрес");
        denied(g, "https://local.partner.ru/in", "этого сервера");
    }

    @Test
    void internalHostNeedsExactNameOrNetwork() {
        assertThat(guard(false, "jira.corp.local").check(URI.create("https://jira.corp.local/rest"))).hasSize(1);
        assertThat(guard(false, "10.1.0.0/16").check(URI.create("https://jira.corp.local/rest"))).hasSize(1);
        assertThat(guard(false, "10.1.0.0/16").check(URI.create("http://10.1.2.3:8080/rest"))).hasSize(1);
        denied(guard(false, "10.1.0.0/16"), "http://10.2.0.1/rest", "не входит");
        // Метаданные не открываются и сетью: 169.254.0.0/16 — служебная.
        denied(guard(false, "169.254.0.0/16"), "http://169.254.169.254/latest", "служебный адрес");
        denied(guard(false, "0.0.0.0/0"), "http://[::1]/x", "этого сервера");
    }

    @Test
    void loopbackOnlyWhenAllowedForDevelopment() {
        denied(guard(false, "127.0.0.1"), "http://127.0.0.1:9000/x", "этого сервера");
        assertThat(guard(true, "127.0.0.1").check(URI.create("http://127.0.0.1:9000/x"))).hasSize(1);
    }

    @Test
    void rulesAreValidated() {
        assertThat(OutboundGuard.validRule("hooks.example.ru")).isTrue();
        assertThat(OutboundGuard.validRule("*.example.ru")).isTrue();
        assertThat(OutboundGuard.validRule("10.0.0.0/8")).isTrue();
        assertThat(OutboundGuard.validRule("fd00::/8")).isTrue();
        assertThat(OutboundGuard.validRule("*")).isFalse();
        assertThat(OutboundGuard.validRule("https://x.ru")).isFalse();
        assertThat(OutboundGuard.validRule("10.0.0.0/33")).isFalse();
        assertThat(OutboundGuard.validRule("a..b")).isFalse();
    }
}
