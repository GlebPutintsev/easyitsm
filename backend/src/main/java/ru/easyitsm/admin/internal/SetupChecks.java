package ru.easyitsm.admin.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Проверки мастера «Проверить почту» и «Проверить SSO» (10.16). Адреса берутся только из настроек приложения,
 * а не из запроса: администратор не может заставить сервер обращаться к произвольному адресу (SSRF).
 * Ответ не содержит секретов и тел ответов серверов, только итог.
 */
@Component
class SetupChecks {

    static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final Environment env;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    SetupChecks(Environment env, ObjectProvider<ClientRegistrationRepository> registrations) {
        this.env = env;
        this.registrations = registrations;
    }

    record Result(String target, boolean ok, String code, String detail, long latencyMs) {
    }

    record Report(boolean configured, boolean ok, List<Result> results) {
    }

    /** SMTP: соединение, приветствие 220 и ответ 250 на EHLO. Письмо не отправляется. */
    Report mail() {
        String host = env.getProperty("spring.mail.host");
        if (host == null || host.isBlank()) {
            return new Report(false, false, List.of(new Result("", false, "not_configured",
                    "Почтовый сервер не задан (spring.mail.host)", 0)));
        }
        int port = env.getProperty("spring.mail.port", Integer.class, 25);
        Result r = smtp(host.trim(), port);
        return new Report(true, r.ok(), List.of(r));
    }

    static Result smtp(String host, int port) {
        String target = host + ":" + port;
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            String greeting = reply(in);
            if (!greeting.startsWith("220")) {
                return new Result(target, false, "bad_greeting", "Сервер не похож на SMTP", ms(started));
            }
            out.write("EHLO easyitsm.check\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String ehlo = reply(in);
            out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (!ehlo.startsWith("250")) {
                return new Result(target, false, "ehlo_rejected", "Сервер отклонил EHLO", ms(started));
            }
            String tls = ehlo.toUpperCase().contains("STARTTLS") ? "есть STARTTLS" : "без STARTTLS";
            return new Result(target, true, "ok", "SMTP отвечает, " + tls, ms(started));
        } catch (IOException e) {
            return new Result(target, false, "unreachable", "Нет соединения с сервером", ms(started));
        }
    }

    /** Многострочный ответ SMTP: строки «250-…» продолжаются, «250 …» завершает. */
    private static String reply(BufferedReader in) throws IOException {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            String line = in.readLine();
            if (line == null) {
                break;
            }
            all.append(line).append('\n');
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
        }
        return all.toString();
    }

    /** OIDC: для каждого настроенного провайдера доступен набор ключей (jwk-set-uri) и в нём есть ключи. */
    Report sso() {
        ClientRegistrationRepository repo = registrations.getIfAvailable();
        if (!(repo instanceof Iterable<?> iterable)) {
            return new Report(false, false, List.of(new Result("", false, "not_configured",
                    "Вход через SSO не настроен", 0)));
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        List<Result> results = new ArrayList<>();
        for (Object o : iterable) {
            if (o instanceof ClientRegistration reg) {
                results.add(jwks(http, reg));
            }
        }
        if (results.isEmpty()) {
            return new Report(false, false, List.of(new Result("", false, "not_configured",
                    "Вход через SSO не настроен", 0)));
        }
        return new Report(true, results.stream().allMatch(Result::ok), results);
    }

    private static Result jwks(HttpClient http, ClientRegistration reg) {
        String name = reg.getClientName() == null ? reg.getRegistrationId() : reg.getClientName();
        String uri = reg.getProviderDetails().getJwkSetUri();
        if (uri == null || uri.isBlank()) {
            return new Result(name, false, "no_jwks", "У провайдера не задан адрес ключей", 0);
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT).GET()
                    .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Result(name, false, "http_" + resp.statusCode(),
                        "Провайдер ответил кодом " + resp.statusCode(), ms(started));
            }
            if (!resp.body().contains("\"keys\"")) {
                return new Result(name, false, "no_keys", "Ответ провайдера без ключей подписи", ms(started));
            }
            return new Result(name, true, "ok", "Провайдер доступен, ключи подписи получены", ms(started));
        } catch (IOException | IllegalArgumentException e) {
            return new Result(name, false, "unreachable", "Нет соединения с провайдером", ms(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(name, false, "interrupted", "Проверка прервана", ms(started));
        }
    }

    private static long ms(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
