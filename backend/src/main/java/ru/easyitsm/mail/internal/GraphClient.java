package ru.easyitsm.mail.internal;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Graph для приёма и отправки почты (CHN-01): вход приложения по client credentials
 * (разрешения Mail.ReadWrite и Mail.Send с ограничением по ящику через Application Access Policy),
 * письма читаются и отправляются в исходном MIME, поэтому разбор и подпись одинаковы с IMAP и SMTP.
 */
@Component
class GraphClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MailProperties props;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();

    GraphClient(MailProperties props) {
        this.props = props;
    }

    record Credentials(String tenant, String clientId, String secret) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    /** Непрочитанные письма папки (до {@code limit}): id в Graph. */
    List<String> unread(Credentials c, String mailbox, String folder, int limit) throws IOException {
        String f = folder == null || folder.equalsIgnoreCase("INBOX") ? "inbox" : enc(folder);
        String url = props.graphApiUrl() + "/users/" + enc(mailbox) + "/mailFolders/" + f
                + "/messages?$filter=" + enc("isRead eq false") + "&$select=id&$orderby=" + enc("receivedDateTime asc")
                + "&$top=" + limit;
        JsonNode body = json.readTree(send(c, HttpRequest.newBuilder(URI.create(url)).GET(), 200));
        List<String> ids = new ArrayList<>();
        for (JsonNode n : body.path("value")) {
            ids.add(n.path("id").asString());
        }
        return ids;
    }

    byte[] mime(Credentials c, String mailbox, String messageId) throws IOException {
        String url = props.graphApiUrl() + "/users/" + enc(mailbox) + "/messages/" + enc(messageId) + "/$value";
        return sendBytes(c, HttpRequest.newBuilder(URI.create(url)).GET());
    }

    void markRead(Credentials c, String mailbox, String messageId) throws IOException {
        String url = props.graphApiUrl() + "/users/" + enc(mailbox) + "/messages/" + enc(messageId);
        send(c, HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"isRead\":true}")), 200);
    }

    /** Отправка готового MIME: тело запроса — Base64 письма (Graph sendMail в формате MIME). */
    void sendMime(Credentials c, String from, byte[] mime) throws IOException {
        String url = props.graphApiUrl() + "/users/" + enc(from) + "/sendMail";
        send(c, HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(Base64.getEncoder().encodeToString(mime))), 202);
    }

    /** Проверка подключения: вход и чтение папки «Входящие». */
    void check(Credentials c, String mailbox) throws IOException {
        String url = props.graphApiUrl() + "/users/" + enc(mailbox) + "/mailFolders/inbox?$select=id";
        send(c, HttpRequest.newBuilder(URI.create(url)).GET(), 200);
    }

    private String send(Credentials c, HttpRequest.Builder req, int expected) throws IOException {
        return new String(sendRaw(c, req, expected), StandardCharsets.UTF_8);
    }

    private byte[] sendBytes(Credentials c, HttpRequest.Builder req) throws IOException {
        return sendRaw(c, req, 200);
    }

    private byte[] sendRaw(Credentials c, HttpRequest.Builder req, int expected) throws IOException {
        HttpRequest request = req.timeout(TIMEOUT).header("Authorization", "Bearer " + token(c)).build();
        HttpResponse<byte[]> resp = call(request);
        if (resp.statusCode() == 401) {
            tokens.remove(key(c));
            resp = call(req.timeout(TIMEOUT).header("Authorization", "Bearer " + token(c)).build());
        }
        if (resp.statusCode() != expected && resp.statusCode() / 100 != 2) {
            throw new IOException("Graph ответил " + resp.statusCode() + errorCode(resp.body()));
        }
        return resp.body();
    }

    private String token(Credentials c) throws IOException {
        Token t = tokens.get(key(c));
        if (t != null && t.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return t.value();
        }
        if (c.secret() == null || c.secret().isBlank()) {
            throw new IOException("Не задан секрет приложения Graph");
        }
        String form = "grant_type=client_credentials&client_id=" + enc(c.clientId()) + "&client_secret="
                + enc(c.secret()) + "&scope=" + enc("https://graph.microsoft.com/.default");
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.graphLoginUrl() + "/" + enc(c.tenant())
                        + "/oauth2/v2.0/token"))
                .timeout(TIMEOUT).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<byte[]> resp = call(req);
        if (resp.statusCode() != 200) {
            throw new IOException("Вход в Microsoft Entra не выполнен: " + resp.statusCode() + errorCode(resp.body()));
        }
        JsonNode body = json.readTree(resp.body());
        Token fresh = new Token(body.path("access_token").asString(),
                Instant.now().plusSeconds(body.path("expires_in").asLong(3600)));
        tokens.put(key(c), fresh);
        return fresh.value();
    }

    private HttpResponse<byte[]> call(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к Graph прерван", e);
        }
    }

    private String errorCode(byte[] body) {
        try {
            JsonNode n = json.readTree(body);
            String code = n.path("error").isObject() ? n.path("error").path("code").asString()
                    : n.path("error").asString();
            return code == null || code.isBlank() ? "" : " (" + code + ")";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String key(Credentials c) {
        return c.tenant() + "|" + c.clientId() + "|" + Integer.toHexString(String.valueOf(c.secret()).hashCode());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
