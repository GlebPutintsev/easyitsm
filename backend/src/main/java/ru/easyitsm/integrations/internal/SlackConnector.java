package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Slack Web API и Socket Mode. Сообщения приходят по WebSocket, который открывает сам сервер
 * ({@link SlackSockets}), поэтому входящий доступ из интернета не нужен. Токен бота ({@code xoxb-}) — для
 * {@code auth.test} и {@code chat.postMessage}, токен приложения ({@code xapp-}) — для
 * {@code apps.connections.open}. Работают только личные сообщения боту (вкладка Messages).
 */
@Component
class SlackConnector implements MessengerConnector {

    @Override
    public String kind() {
        return "slack";
    }

    @Override
    public String title() {
        return "Slack";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://slack.com/api";
    }

    @Override
    public boolean polls() {
        return false;
    }

    @Override
    public boolean needsAppToken() {
        return true;
    }

    private static Map<String, String> auth(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    /** Slack отвечает 200 и {@code ok: false} с кодом ошибки. */
    private static JsonNode ok(Api api, JsonNode node) {
        if (!node.path("ok").asBoolean(false)) {
            throw new Failure("ошибка Slack: " + AlertFormats.cut(api.scrub(node.path("error").asString("unknown")), 100));
        }
        return node;
    }

    @Override
    public Bot me(Api api) {
        JsonNode r = ok(api, api.post("/auth.test", auth(api.token()), Map.of()));
        String userId = r.path("user_id").asString(null);
        String url = r.path("url").asString(null);
        String chat = userId == null || url == null || !url.startsWith("https://") ? null
                : url.replaceAll("/+$", "") + "/team/" + userId;
        return new Bot(userId == null ? "" : userId, r.path("user").asString(null), r.path("team").asString(null), chat);
    }

    @Override
    public void prepare(Api api) {
        open(api);
    }

    /** Адрес WebSocket для Socket Mode: одноразовый, действует несколько минут. */
    URI open(Api api) {
        if (api.appToken() == null) {
            throw new Failure("нет токена приложения");
        }
        JsonNode r = ok(api, api.post("/apps.connections.open", auth(api.appToken()), Map.of()));
        String url = r.path("url").asString("");
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new Failure("Slack вернул неверный адрес соединения");
        }
    }

    @Override
    public Poll poll(Api api, String cursor) {
        throw new UnsupportedOperationException("Slack не опрашивается: сообщения приходят по Socket Mode");
    }

    @Override
    public String send(Api api, String chatId, String text, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", chatId);
        body.put("text", escape(text));
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        if (replyTo != null) {
            body.put("thread_ts", replyTo);
        }
        JsonNode r = ok(api, api.post("/chat.postMessage", auth(api.token()), body));
        return r.path("ts").asString(null);
    }

    @Override
    public String deepLink(MessengerStore.Messenger bot, String code) {
        return bot.botUrl();
    }

    /** В Slack сообщения с «/» — команды самого Slack, поэтому код отправляется как есть. */
    @Override
    public String linkCommand(String code) {
        return code;
    }

    /**
     * Сообщение из конверта Socket Mode {@code events_api}: личное сообщение человека боту, иначе null. Ответ в
     * ветке ({@code thread_ts}) ссылается на сообщение, с которого ветка началась.
     */
    static Update parse(JsonNode envelope) {
        JsonNode e = envelope.path("payload").path("event");
        if (!"message".equals(e.path("type").asString("")) || !"im".equals(e.path("channel_type").asString(""))) {
            return null;
        }
        // Правки, удаления, сообщения ботов (в том числе свои) пропускаются.
        if (!e.path("subtype").isMissingNode() || !e.path("bot_id").isMissingNode() || e.path("user").isMissingNode()) {
            return null;
        }
        String ts = e.path("ts").asString(null);
        String channel = e.path("channel").asString(null);
        if (ts == null || channel == null) {
            return null;
        }
        String thread = e.path("thread_ts").asString(null);
        return new Update(channel, e.path("user").asString(null), ts, unescape(e.path("text").asString("")),
                thread == null || thread.equals(ts) ? null : thread, null);
    }

    /** Slack экранирует в тексте только три символа. */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
