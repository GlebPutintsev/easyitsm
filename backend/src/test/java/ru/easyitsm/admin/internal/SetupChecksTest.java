package ru.easyitsm.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** «Проверить почту» против поддельного SMTP-сервера. */
class SetupChecksTest {

    @Test
    void smtpGreetingAndEhlo() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (var s = server.accept();
                     var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                     var out = new PrintWriter(s.getOutputStream(), true)) {
                    out.print("220 mail.test ESMTP\r\n");
                    out.flush();
                    in.readLine();
                    out.print("250-mail.test\r\n250-STARTTLS\r\n250 SIZE 1000\r\n");
                    out.flush();
                    in.readLine();
                } catch (Exception ignored) {
                    // клиент закрыл соединение
                }
            });
            t.start();
            var r = SetupChecks.smtp("127.0.0.1", server.getLocalPort());
            t.join(5000);
            assertThat(r.ok()).isTrue();
            assertThat(r.detail()).contains("STARTTLS");
        }
    }

    @Test
    void closedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var r = SetupChecks.smtp("127.0.0.1", port);
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo("unreachable");
    }
}
