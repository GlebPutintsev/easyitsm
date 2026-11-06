package ru.easyitsm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Доставка в SIEM (SEC-32): записи уходят по порядку в рамках Syslog с длиной, номер доставленной записи
 * запоминается, при недоступном приёмнике — ошибка сохраняется и назначается повтор, после восстановления
 * ничего не теряется. Приёмник — локальный TCP-сервер теста.
 */
class SiemForwarderTest extends AuditTestSupport {

    static final int PORT;
    static ServerSocket server;

    static {
        try {
            server = new ServerSocket(0);
            server.setSoTimeout(5000);
            PORT = server.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reopen() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress("127.0.0.1", PORT));
        server.setSoTimeout(5000);
    }

    @DynamicPropertySource
    static void siem(DynamicPropertyRegistry registry) {
        registry.add("easyitsm.audit.siem.enabled", () -> "true");
        registry.add("easyitsm.audit.siem.host", () -> "127.0.0.1");
        registry.add("easyitsm.audit.siem.port", () -> PORT);
        registry.add("easyitsm.audit.siem.transport", () -> "TCP");
        registry.add("easyitsm.audit.siem.format", () -> "CEF");
        registry.add("easyitsm.audit.siem.connect-timeout", () -> "2s");
        // Плановая задача не мешает: в тесте отправку вызываем сами
        registry.add("easyitsm.audit.siem.interval", () -> "PT1H");
        registry.add("easyitsm.audit.siem.initial-delay", () -> "PT1H");
    }

    @AfterAll
    static void close() throws IOException {
        server.close();
    }

    @Autowired
    SiemForwarder forwarder;

    @Test
    void deliversInOrderAndRetriesAfterFailure() throws Exception {
        UUID tenant = newTenant();
        TenantContext.runAs(tenant, () -> {
            for (int i = 1; i <= 3; i++) {
                writer.record(AuditEntry.of(AuditCategory.AUTH, "auth.login").object("user", "u" + i)
                        .detail("password", "Secret-" + i));
            }
        });
        assertThat(forwarder.pendingTenants(1000)).contains(tenant);

        try (Socket client = acceptAsync(() -> assertThat(deliver(tenant)).isEqualTo(3))) {
            List<String> frames = readFrames(client.getInputStream(), 3);
            assertThat(frames).hasSize(3);
            for (int i = 0; i < 3; i++) {
                String f = frames.get(i);
                assertThat(f).startsWith("<110>1 ").contains(" easyitsm - auth.login - CEF:0|EasyITSM|Easy ITSM|");
                assertThat(f).contains("cs4=u" + (i + 1)).contains("cn1=" + (i + 1)).contains("outcome=success");
                assertThat(f).doesNotContain("Secret-");
            }
        }
        assertThat(state(tenant, "delivered_seq")).isEqualTo("3");
        assertThat(forwarder.pendingTenants(1000)).doesNotContain(tenant);

        // Приёмник недоступен: запись не теряется, ошибка и время повтора сохранены
        server.close();
        TenantContext.runAs(tenant, () -> writer.record(AuditEntry.of(AuditCategory.AUTH, "auth.logout")));
        assertThat(deliver(tenant)).isZero();
        assertThat(state(tenant, "failures")).isEqualTo("1");
        assertThat(state(tenant, "last_error")).isNotBlank();
        assertThat(state(tenant, "delivered_seq")).isEqualTo("3");
        // Пауза ещё не прошла — попытки нет
        assertThat(deliver(tenant)).isZero();
        assertThat(state(tenant, "failures")).isEqualTo("1");

        // Приёмник снова доступен — доставляется то, что накопилось
        reopen();
        TenantContext.runAs(tenant, () -> jdbc.sql("update audit_siem_state set next_attempt_at = now()").update());
        try (Socket client = acceptAsync(() -> assertThat(deliver(tenant)).isEqualTo(1))) {
            String frame = readFrames(client.getInputStream(), 1).getFirst();
            assertThat(frame).contains("cn1=4").contains("auth.logout");
        }
        assertThat(state(tenant, "delivered_seq")).isEqualTo("4");
        assertThat(state(tenant, "failures")).isEqualTo("0");
    }

    @Test
    void backoffGrowsUpToLimit() {
        var max = java.time.Duration.ofMinutes(5);
        assertThat(SiemForwarder.backoff(1, max)).hasSeconds(5);
        assertThat(SiemForwarder.backoff(2, max)).hasSeconds(10);
        assertThat(SiemForwarder.backoff(4, max)).hasSeconds(40);
        assertThat(SiemForwarder.backoff(30, max)).isEqualTo(max);
    }

    private int deliver(UUID tenant) {
        int[] n = {0};
        TenantContext.runAs(tenant, () -> n[0] = forwarder.deliverBatch());
        return n[0];
    }

    private String state(UUID tenant, String column) {
        return in(tenant.toString(), () -> jdbc.sql("select " + column + "::text from audit_siem_state")
                .query(String.class).single());
    }

    /** Отправка в отдельном потоке, тест принимает соединение. */
    private Socket acceptAsync(Runnable send) throws Exception {
        List<Throwable> errors = new ArrayList<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                send.run();
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        Socket client = server.accept();
        client.setSoTimeout(5000);
        t.join(10_000);
        if (!errors.isEmpty()) {
            client.close();
            throw new AssertionError(errors.getFirst());
        }
        return client;
    }

    /** Рамки RFC 6587 «длина пробел сообщение». */
    static List<String> readFrames(InputStream in, int count) throws IOException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder len = new StringBuilder();
            int c;
            while ((c = in.read()) != ' ') {
                if (c < 0) {
                    return out;
                }
                len.append((char) c);
            }
            byte[] buf = in.readNBytes(Integer.parseInt(len.toString()));
            out.add(new String(buf, StandardCharsets.UTF_8));
        }
        return out;
    }
}
