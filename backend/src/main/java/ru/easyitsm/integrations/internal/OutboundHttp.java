package ru.easyitsm.integrations.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Единая точка исходящих запросов модуля (раздел 8.5 ТЗ). Перед запросом и перед каждым перенаправлением
 * адрес проверяет {@link OutboundGuard}; сам клиент перенаправлениям не следует. Следуем только 307 и 308
 * (тело сохраняется), не больше трёх раз. Тело ответа читается не больше 4 КБ — для журнала.
 */
@Component
class OutboundHttp {

    static final int MAX_REDIRECTS = 3;

    /** Итог запроса: код ответа или ошибка до получения ответа. */
    record Result(int status, String error, String responseSnippet) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client;
    private final IntegrationsProperties props;

    OutboundHttp(IntegrationsProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Ответ запроса к API (мессенджеры): тело целиком, но не больше {@code maxBytes}. */
    record Response(int status, String error, byte[] body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        String text() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Запрос к API внешней системы без перенаправлений. {@code body} null — GET. Текст ошибки не содержит адреса:
     * в пути бывает токен (Telegram).
     */
    Response call(OutboundGuard guard, URI uri, Map<String, String> headers, byte[] body, int maxBytes) {
        try {
            guard.check(uri);
        } catch (OutboundGuard.Denied e) {
            return new Response(0, "адрес запрещён: " + e.getMessage(), null);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(props.outbound().timeout());
        if (body == null) {
            req.GET();
        } else {
            req.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        headers.forEach(req::header);
        try {
            HttpResponse<java.io.InputStream> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var in = resp.body()) {
                byte[] bytes = in.readNBytes(maxBytes);
                return new Response(resp.statusCode(), null, bytes);
            }
        } catch (IOException e) {
            return new Response(0, "нет соединения: " + e.getClass().getSimpleName(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, "прервано", null);
        }
    }

    OutboundGuard guard(List<String> allowedHosts) {
        return new OutboundGuard(allowedHosts, props.outbound().allowLoopback(), InetAddress::getAllByName);
    }

    Result post(OutboundGuard guard, URI uri, Map<String, String> headers, byte[] body) {
        URI target = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            try {
                guard.check(target);
            } catch (OutboundGuard.Denied e) {
                return new Result(0, "адрес запрещён: " + e.getMessage(), null);
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(target)
                    .timeout(props.outbound().timeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(req::header);
            HttpResponse<java.io.InputStream> resp;
            try {
                resp = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                return new Result(0, "нет соединения: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + AlertFormats.cut(e.getMessage(), 200)), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(0, "прервано", null);
            }
            String snippet = snippet(resp);
            int status = resp.statusCode();
            if ((status == 307 || status == 308) && hop < MAX_REDIRECTS) {
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return new Result(status, "перенаправление без адреса", snippet);
                }
                try {
                    target = target.resolve(location);
                } catch (IllegalArgumentException e) {
                    return new Result(status, "неверный адрес перенаправления", snippet);
                }
                continue;
            }
            if (status >= 300 && status < 400) {
                return new Result(status, "получатель ответил перенаправлением", snippet);
            }
            return new Result(status, status >= 200 && status < 300 ? null : "ответ HTTP " + status, snippet);
        }
        return new Result(0, "слишком много перенаправлений", null);
    }

    private static String snippet(HttpResponse<java.io.InputStream> resp) {
        try (var in = resp.body()) {
            byte[] b = in.readNBytes(4096);
            String s = new String(b, StandardCharsets.UTF_8).strip();
            return s.isEmpty() ? null : AlertFormats.cut(s.replaceAll("\\s+", " "), 300);
        } catch (IOException e) {
            return null;
        }
    }
}
