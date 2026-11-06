package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Коннектор мессенджера (CHN-04): проверка бота, опрос новых сообщений и отправка. Все запросы идут через
 * {@link OutboundHttp} с проверкой адреса; токен бота не попадает в тексты ошибок.
 */
interface MessengerConnector {

    /** Ошибка API мессенджера: текст — для журнала и экрана администратора. */
    final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    /** Бот по токену. */
    record Bot(String id, String username, String name) {
    }

    /**
     * Входящее сообщение личного чата.
     *
     * @param chatId       чат, куда отвечать
     * @param messageId    id сообщения в чате
     * @param replyTo      id сообщения, на которое ответил пользователь, или null
     * @param startPayload код из ссылки запуска бота или null
     */
    record Update(String chatId, String fromName, String messageId, String text, String replyTo,
                  String startPayload) {
    }

    /** Итог опроса: сообщения и новая позиция. */
    record Poll(List<Update> updates, String cursor) {
    }

    /** Подключение к API: адрес, токен и проверка исходящих адресов. */
    record Api(OutboundHttp http, OutboundGuard guard, String baseUrl, String token, JsonMapper json) {

        JsonNode get(String path, Map<String, String> headers) {
            return parse(http.call(guard, URI.create(baseUrl + path), headers, null, MAX_RESPONSE));
        }

        JsonNode post(String path, Map<String, String> headers, Object body) {
            Map<String, String> h = new java.util.LinkedHashMap<>(headers);
            h.put("Content-Type", "application/json");
            byte[] bytes;
            try {
                bytes = json.writeValueAsBytes(body);
            } catch (JacksonException e) {
                throw new Failure("не удалось подготовить запрос");
            }
            return parse(http.call(guard, URI.create(baseUrl + path), h, bytes, MAX_RESPONSE));
        }

        private JsonNode parse(OutboundHttp.Response r) {
            if (r.status() == 0) {
                throw new Failure(scrub(r.error()));
            }
            JsonNode node;
            try {
                node = json.readTree(r.body() == null ? new byte[0] : r.body());
            } catch (JacksonException e) {
                throw new Failure("ответ HTTP " + r.status() + " не JSON");
            }
            if (!r.ok()) {
                String detail = node.path("description").asString(node.path("message").asString(""));
                throw new Failure("ответ HTTP " + r.status() + (detail.isBlank() ? "" : ": " + AlertFormats.cut(scrub(detail), 200)));
            }
            return node;
        }

        private String scrub(String s) {
            return s == null ? "" : token == null || token.isEmpty() ? s : s.replace(token, "…");
        }
    }

    int MAX_RESPONSE = 1 << 20;

    String kind();

    /** Официальный адрес API: разрешён без списка администратора. */
    String defaultBaseUrl();

    Bot me(Api api);

    /** Подготовка к опросу (Telegram: снять вебхук, иначе getUpdates не работает). */
    default void prepare(Api api) {
    }

    Poll poll(Api api, String cursor);

    /** @return id отправленного сообщения или null */
    String send(Api api, String chatId, String text, String replyTo);

    /** Ссылка, открывающая бот с кодом привязки. */
    String deepLink(String username, String payload);

}
