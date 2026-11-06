package ru.easyitsm.search.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Тонкий HTTP-клиент OpenSearch REST API: без отдельной библиотеки, чтобы не тянуть зависимости в закрытый
 * контур. Узлы перебираются по кругу, недоступный пропускается. Ошибка сети или ответ 5xx —
 * {@link SearchUnavailable}: поиск переходит на резервный движок, индексатор повторит позже.
 */
@Component
class OpenSearchClient {

    /** OpenSearch недоступен или ответил ошибкой сервера. */
    static class SearchUnavailable extends RuntimeException {
        SearchUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Ответ OpenSearch. */
    record Response(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final SearchProperties.OpenSearch props;
    private final JsonMapper json;
    private final HttpClient http;
    private final AtomicInteger next = new AtomicInteger();

    OpenSearchClient(SearchProperties props, JsonMapper json) {
        this.props = props.opensearch();
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(this.props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    boolean enabled() {
        return props.enabled();
    }

    Response get(String path) {
        return send("GET", path, null, "application/json", props.timeout());
    }

    Response head(String path) {
        return send("HEAD", path, null, "application/json", props.timeout());
    }

    Response put(String path, Object body) {
        return send("PUT", path, json.writeValueAsString(body), "application/json", props.timeout());
    }

    Response post(String path, Object body) {
        return send("POST", path, json.writeValueAsString(body), "application/json", props.timeout());
    }

    Response delete(String path) {
        return send("DELETE", path, null, "application/json", props.timeout());
    }

    /** Bulk-запрос в формате NDJSON: строки уже сериализованы. Индексация ждёт дольше поиска. */
    Response bulkRequireAlias(List<String> lines) {
        String body = String.join("\n", lines) + "\n";
        return send("POST", "/_bulk?require_alias=true", body, "application/x-ndjson",
                props.timeout().multipliedBy(5));
    }

    /** Мультипоиск: пары строк «заголовок, запрос». */
    Response msearch(List<Object> headerAndBodies) {
        StringBuilder sb = new StringBuilder();
        for (Object o : headerAndBodies) {
            sb.append(json.writeValueAsString(o)).append('\n');
        }
        return send("POST", "/_msearch", sb.toString(), "application/x-ndjson", props.timeout());
    }

    String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private Response send(String method, String path, String body, String contentType, Duration timeout) {
        List<String> urls = props.urls();
        if (urls == null || urls.isEmpty()) {
            throw new SearchUnavailable("Не заданы адреса OpenSearch", null);
        }
        RuntimeException last = null;
        int start = Math.floorMod(next.getAndIncrement(), urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String base = urls.get((start + i) % urls.size()).replaceAll("/+$", "");
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                        .header("Accept", "application/json");
                if (props.username() != null && !props.username().isBlank()) {
                    String token = props.username() + ":" + (props.password() == null ? "" : props.password());
                    rb.header("Authorization",
                            "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
                }
                if (body != null) {
                    rb.header("Content-Type", contentType);
                    rb.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                } else {
                    rb.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() >= 500) {
                    last = new SearchUnavailable("OpenSearch " + r.statusCode() + ": " + abbreviate(r.body()), null);
                    continue;
                }
                JsonNode node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
                return new Response(r.statusCode(), node);
            } catch (IOException e) {
                last = new SearchUnavailable("OpenSearch недоступен: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchUnavailable("Запрос к OpenSearch прерван", e);
            } catch (IllegalArgumentException e) {
                throw new SearchUnavailable("Неверный адрес OpenSearch: " + base, e);
            }
        }
        throw last;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
