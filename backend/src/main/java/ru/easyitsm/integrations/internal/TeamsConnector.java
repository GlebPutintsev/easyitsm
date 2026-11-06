package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Microsoft Teams через Azure Bot Service. Сообщения присылает Bot Framework на {@link TeamsController}, ответы
 * уходят на {@code serviceUrl} разговора ({@code POST v3/conversations/{id}/activities}) с токеном приложения из
 * Microsoft Entra (client credentials). В {@code token} бота хранится секрет клиента. Работают только личные
 * разговоры с ботом.
 */
@Component
class TeamsConnector implements MessengerConnector {

    /** Узлы Bot Framework, куда разрешено отвечать: serviceUrl приходит в запросе, поэтому список жёсткий. */
    static final List<String> SERVICE_HOSTS = List.of("smba.trafficmanager.net", ".botframework.com",
            ".botframework.us", ".teams.microsoft.com");
    private static final Pattern REPLY = Pattern.compile(
            "itemtype=\"http://schema\\.skype\\.com/Reply\"[^>]*itemid=\"([^\"]+)\"|itemid=\"([^\"]+)\"[^>]*itemtype=\"http://schema\\.skype\\.com/Reply\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private record Cached(String token, Instant expires) {
    }

    private final IntegrationsProperties props;
    private final MessengerStore store;
    private final Map<String, Cached> tokens = new ConcurrentHashMap<>();

    TeamsConnector(IntegrationsProperties props, MessengerStore store) {
        this.props = props;
        this.store = store;
    }

    @Override
    public String kind() {
        return "teams";
    }

    @Override
    public String title() {
        return "Microsoft Teams";
    }

    /** Microsoft Entra: выдача токена приложения. */
    @Override
    public String defaultBaseUrl() {
        return props.teams().loginUrl();
    }

    @Override
    public boolean polls() {
        return false;
    }

    /** Токен приложения для Bot Framework; кешируется до минуты до истечения. */
    String accessToken(Api api) {
        if (api.appId() == null || api.appTenant() == null) {
            throw new Failure("не указаны Microsoft App ID и арендатор");
        }
        String key = api.appTenant() + "|" + api.appId() + "|" + HexFormat.of().formatHex(MessengerService.hash(api.token()));
        Cached c = tokens.get(key);
        if (c != null && c.expires().isAfter(Instant.now().plusSeconds(60))) {
            return c.token();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", api.appId());
        form.put("client_secret", api.token());
        form.put("scope", props.teams().scope());
        JsonNode r = api.postForm("/" + api.appTenant() + "/oauth2/v2.0/token", form);
        String token = r.path("access_token").asString(null);
        if (token == null || token.isBlank()) {
            throw new Failure("Microsoft Entra не выдал токен приложения");
        }
        long ttl = Math.max(120, r.path("expires_in").asLong(3600));
        tokens.put(key, new Cached(token, Instant.now().plusSeconds(ttl)));
        return token;
    }

    @Override
    public Bot me(Api api) {
        accessToken(api);
        return new Bot(api.appId(), api.appId(), null, "https://teams.microsoft.com/l/chat/0/0?users=28:" + api.appId());
    }

    @Override
    public Poll poll(Api api, String cursor) {
        throw new UnsupportedOperationException("Teams не опрашивается: сообщения присылает Bot Framework");
    }

    @Override
    public String send(Api api, String chatId, String text, String replyTo) {
        throw new Failure("для Teams нужен адрес разговора");
    }

    @Override
    public String send(Api api, MessengerStore.Messenger bot, String chatId, String text, String replyTo) {
        String serviceUrl = store.serviceUrl(bot.id(), chatId)
                .orElseThrow(() -> new Failure("разговор Teams неизвестен: пользователь ещё не писал боту"));
        URI base = serviceUri(serviceUrl, api.http().loopbackAllowed());
        if (base == null) {
            throw new Failure("недопустимый адрес Bot Framework");
        }
        URI uri = URI.create(base.toString().replaceAll("/+$", "") + "/v3/conversations/"
                + URLEncoder.encode(chatId, StandardCharsets.UTF_8).replace("+", "%20") + "/activities");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "message");
        body.put("text", text);
        body.put("textFormat", "plain");
        if (replyTo != null) {
            body.put("replyToId", replyTo);
        }
        JsonNode r = api.postTo(api.http().guard(List.of(base.getHost())), uri,
                Map.of("Authorization", "Bearer " + accessToken(api)), body);
        return r.path("id").asString(null);
    }

    @Override
    public String deepLink(MessengerStore.Messenger bot, String code) {
        return bot.botUrl();
    }

    /** Команд с кодом у Teams нет: код отправляется сообщением. */
    @Override
    public String linkCommand(String code) {
        return code;
    }

    /**
     * Адрес Bot Framework из запроса: только https и известные узлы Microsoft (в тестах — адрес этого сервера).
     *
     * @return адрес или null, если он не подходит
     */
    static URI serviceUri(String serviceUrl, boolean allowLoopback) {
        URI uri;
        try {
            uri = new URI(serviceUrl);
        } catch (java.net.URISyntaxException | NullPointerException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null || host.isEmpty()) {
            return null;
        }
        boolean microsoft = scheme.equals("https")
                && SERVICE_HOSTS.stream().anyMatch(h -> h.startsWith(".") ? host.endsWith(h) : host.equals(h));
        boolean local = allowLoopback && scheme.equals("http") && OutboundGuard.isLiteral(host);
        return microsoft || local ? uri : null;
    }

    /**
     * Сообщение личного разговора из activity Bot Framework, иначе null. Ответ с цитатой Teams передаёт id
     * исходного сообщения в HTML-вложении ({@code itemtype=".../Reply" itemid="…"}).
     */
    static Update parse(JsonNode a) {
        if (!"message".equals(a.path("type").asString(""))) {
            return null;
        }
        JsonNode conv = a.path("conversation");
        if (!"personal".equals(conv.path("conversationType").asString("personal")) || conv.path("isGroup").asBoolean(false)) {
            return null;
        }
        String chat = conv.path("id").asString(null);
        String id = a.path("id").asString(null);
        if (chat == null || id == null) {
            return null;
        }
        String replyTo = a.path("replyToId").asString(null);
        for (JsonNode att : a.path("attachments")) {
            if ("text/html".equals(att.path("contentType").asString(""))) {
                Matcher m = REPLY.matcher(att.path("content").asString(""));
                if (m.find()) {
                    replyTo = m.group(1) != null ? m.group(1) : m.group(2);
                }
            }
        }
        return new Update(chat, a.path("from").path("name").asString(null), id, text(a.path("text").asString("")),
                replyTo, null);
    }

    /** Текст без упоминаний и разметки. */
    static String text(String s) {
        String t = s.replaceAll("(?s)<at>.*?</at>", "");
        if (t.contains("<")) {
            t = TAG.matcher(t.replaceAll("(?i)<br\\s*/?>", "\n")).replaceAll("");
            t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&amp;", "&");
        }
        return t.strip();
    }
}
