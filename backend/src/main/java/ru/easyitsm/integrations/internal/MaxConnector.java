package ru.easyitsm.integrations.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * API ботов MAX (platform-api.max.ru): опрос {@code GET /updates}, отправка {@code POST /messages}. Токен — в
 * заголовке {@code Authorization}. Работают только личные диалоги с ботом.
 */
@Component
class MaxConnector implements MessengerConnector {

    @Override
    public String kind() {
        return "max";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://platform-api.max.ru";
    }

    private static Map<String, String> auth(Api api) {
        return Map.of("Authorization", api.token());
    }

    @Override
    public Bot me(Api api) {
        JsonNode r = api.get("/me", auth(api));
        return new Bot(r.path("user_id").asString(""), r.path("username").asString(null), r.path("name").asString(null));
    }

    @Override
    public Poll poll(Api api, String cursor) {
        String path = "/updates?limit=100&timeout=0&types=message_created,bot_started"
                + (cursor == null ? "" : "&marker=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return parse(api.get(path, auth(api)), cursor);
    }

    /** Разбор ответа /updates: message_created и bot_started; позиция — marker ответа. */
    static Poll parse(JsonNode result, String cursor) {
        List<Update> out = new ArrayList<>();
        for (JsonNode u : result.path("updates")) {
            switch (u.path("update_type").asString("")) {
                case "bot_started" -> {
                    String payload = u.path("payload").asString(null);
                    out.add(new Update(u.path("chat_id").asString(), u.path("user").path("name").asString(null), null,
                            "/start", null, payload == null || payload.isBlank() ? null : payload.strip()));
                }
                case "message_created" -> {
                    JsonNode m = u.path("message");
                    if (!"dialog".equals(m.path("recipient").path("chat_type").asString("dialog"))) {
                        continue;
                    }
                    String text = m.path("body").path("text").asString("");
                    String payload = null;
                    if (text.startsWith("/start")) {
                        String rest = text.substring("/start".length()).strip();
                        payload = rest.isEmpty() ? null : rest;
                    }
                    JsonNode link = m.path("link");
                    String replyTo = "reply".equals(link.path("type").asString(""))
                            ? link.path("message").path("mid").asString(null) : null;
                    out.add(new Update(m.path("recipient").path("chat_id").asString(),
                            m.path("sender").path("name").asString(null), m.path("body").path("mid").asString(null), text,
                            replyTo, payload));
                }
                default -> {
                    // другие события бота не нужны
                }
            }
        }
        JsonNode marker = result.path("marker");
        return new Poll(out, marker.isMissingNode() || marker.isNull() ? cursor : marker.asString());
    }

    @Override
    public String send(Api api, String chatId, String text, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        if (replyTo != null) {
            body.put("link", Map.of("type", "reply", "mid", replyTo));
        }
        JsonNode r = api.post("/messages?chat_id=" + URLEncoder.encode(chatId, StandardCharsets.UTF_8), auth(api), body);
        return r.path("message").path("body").path("mid").asString(null);
    }

    @Override
    public String deepLink(String username, String payload) {
        return "https://max.ru/" + username + "?start=" + payload;
    }
}
