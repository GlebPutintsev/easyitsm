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

    /** Бот по токену; {@code url} открывает чат с ботом. */
    record Bot(String id, String username, String name, String url) {
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

    /**
     * Подключение к API: адрес, токен бота (у Teams — секрет клиента), токен приложения (Slack), App ID и арендатор
     * Entra (Teams) и проверка исходящих адресов. Ненужные мессенджеру поля — null.
     */
    record Api(OutboundHttp http, OutboundGuard guard, String baseUrl, String token, String appToken, String appId,
               String appTenant, JsonMapper json) {

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

        /** Форма {@code application/x-www-form-urlencoded} (выдача токена OAuth). */
        JsonNode postForm(String path, Map<String, String> form) {
            String body = form.entrySet().stream()
                    .map(e -> java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8) + "="
                            + java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
            return parse(http.call(guard, URI.create(baseUrl + path),
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8), MAX_RESPONSE));
        }

        /** JSON на другой адрес со своей проверкой узла (Teams: serviceUrl разговора). */
        JsonNode postTo(OutboundGuard target, URI uri, Map<String, String> headers, Object body) {
            Map<String, String> h = new java.util.LinkedHashMap<>(headers);
            h.put("Content-Type", "application/json");
            try {
                return parse(http.call(target, uri, h, json.writeValueAsBytes(body), MAX_RESPONSE));
            } catch (JacksonException e) {
                throw new Failure("не удалось подготовить запрос");
            }
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
                String detail = node.path("description").asString(node.path("message").asString(
                        node.path("error_description").asString(node.path("error").path("message").asString(""))));
                throw new Failure("ответ HTTP " + r.status() + (detail.isBlank() ? "" : ": " + AlertFormats.cut(scrub(detail), 200)));
            }
            return node;
        }

        String scrub(String s) {
            if (s == null) {
                return "";
            }
            String out = token == null || token.isEmpty() ? s : s.replace(token, "…");
            return appToken == null || appToken.isEmpty() ? out : out.replace(appToken, "…");
        }
    }

    int MAX_RESPONSE = 1 << 20;

    String kind();

    /** Официальный адрес API: разрешён без списка администратора. */
    String defaultBaseUrl();

    Bot me(Api api);

    /** Подготовка к опросу (Telegram: снять вебхук, иначе getUpdates не работает; Slack: проверить токен приложения). */
    default void prepare(Api api) {
    }

    /** false — сообщения приходят по постоянному соединению ({@link SlackSockets}), опрашивать не нужно. */
    default boolean polls() {
        return true;
    }

    /** Нужен ли токен приложения кроме токена бота. */
    default boolean needsAppToken() {
        return false;
    }

    Poll poll(Api api, String cursor);

    /** @return id отправленного сообщения или null */
    String send(Api api, String chatId, String text, String replyTo);

    /** Отправка с ботом: Teams берёт из него адрес разговора. */
    default String send(Api api, MessengerStore.Messenger bot, String chatId, String text, String replyTo) {
        return send(api, chatId, text, replyTo);
    }

    /** Ссылка, открывающая бот (с кодом привязки, если мессенджер это умеет). */
    String deepLink(MessengerStore.Messenger bot, String code);

    /** Что отправить боту вручную, если ссылка не открылась. */
    default String linkCommand(String code) {
        return "/start " + code;
    }

    /** Название мессенджера для текстов. */
    String title();

}
