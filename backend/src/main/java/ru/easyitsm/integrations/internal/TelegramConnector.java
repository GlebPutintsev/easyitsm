package ru.easyitsm.integrations.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Telegram Bot API: опрос {@code getUpdates}, отправка {@code sendMessage}. Токен — часть пути запроса.
 * Работают только личные чаты с ботом: сообщения групп пропускаются.
 */
@Component
class TelegramConnector implements MessengerConnector {

    @Override
    public String kind() {
        return "telegram";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.telegram.org";
    }

    private static String method(Api api, String name) {
        return "/bot" + api.token() + "/" + name;
    }

    private static JsonNode result(JsonNode node) {
        if (!node.path("ok").asBoolean(false)) {
            throw new Failure(AlertFormats.cut(node.path("description").asString("ошибка Telegram"), 200));
        }
        return node.path("result");
    }

    @Override
    public Bot me(Api api) {
        JsonNode r = result(api.post(method(api, "getMe"), Map.of(), Map.of()));
        String username = r.path("username").asString(null);
        return new Bot(r.path("id").asString(""), username, r.path("first_name").asString(null),
                username == null ? null : "https://t.me/" + username);
    }

    @Override
    public void prepare(Api api) {
        result(api.post(method(api, "deleteWebhook"), Map.of(), Map.of("drop_pending_updates", false)));
    }

    @Override
    public Poll poll(Api api, String cursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (cursor != null) {
            body.put("offset", Long.parseLong(cursor));
        }
        body.put("timeout", 0);
        body.put("limit", 100);
        body.put("allowed_updates", List.of("message"));
        return parse(result(api.post(method(api, "getUpdates"), Map.of(), body)), cursor);
    }

    /** Разбор ответа getUpdates; позиция — последний update_id + 1. */
    static Poll parse(JsonNode result, String cursor) {
        List<Update> out = new ArrayList<>();
        String next = cursor;
        for (JsonNode u : result) {
            long id = u.path("update_id").asLong();
            next = Long.toString(id + 1);
            JsonNode m = u.path("message");
            if (m.isMissingNode() || !"private".equals(m.path("chat").path("type").asString(""))) {
                continue;
            }
            String text = m.path("text").asString(m.path("caption").asString(""));
            String payload = null;
            if (text.startsWith("/start")) {
                String rest = text.substring("/start".length()).strip();
                payload = rest.isEmpty() ? null : rest;
            }
            JsonNode from = m.path("from");
            String name = (from.path("first_name").asString("") + " " + from.path("last_name").asString("")).strip();
            if (name.isEmpty()) {
                name = from.path("username").asString(null);
            }
            JsonNode reply = m.path("reply_to_message");
            out.add(new Update(m.path("chat").path("id").asString(), name, m.path("message_id").asString(), text,
                    reply.isMissingNode() ? null : reply.path("message_id").asString(null), payload));
        }
        return new Poll(out, next);
    }

    @Override
    public String send(Api api, String chatId, String text, String replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (replyTo != null) {
            body.put("reply_parameters", Map.of("message_id", Long.parseLong(replyTo), "allow_sending_without_reply", true));
        }
        JsonNode r = result(api.post(method(api, "sendMessage"), Map.of(), body));
        return r.path("message_id").asString(null);
    }

    @Override
    public String deepLink(MessengerStore.Messenger bot, String code) {
        return "https://t.me/" + bot.botUsername() + "?start=" + code;
    }

    @Override
    public String title() {
        return "Telegram";
    }
}
