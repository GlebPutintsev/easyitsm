package ru.easyitsm.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.databind.JsonNode;

/**
 * Разбор форматов источников в {@link Alert}.
 * <ul>
 *   <li>{@code zabbix} — тело, которое шлёт скрипт типа оповещения из {@code docs/integrations/zabbix-webhook.js}:
 *       {@code event_id}, {@code trigger_id}, {@code status} PROBLEM / RESOLVED / UPDATE, {@code severity},
 *       {@code name}, {@code host}, {@code message}, {@code url}, {@code tags};</li>
 *   <li>{@code alertmanager} — вебхук Prometheus Alertmanager (версия 4) и Grafana Alerting: массив
 *       {@code alerts} с {@code labels}, {@code annotations}, {@code fingerprint};</li>
 *   <li>{@code generic} — один объект или массив {@code events} из {@code fingerprint}, {@code status},
 *       {@code severity}, {@code title}, {@code description}, {@code host}, {@code url}, {@code details}.</li>
 * </ul>
 * Ошибка формата — 400 {@code invalid_event}.
 */
final class AlertFormats {

    static final int MAX_TITLE = 255;
    static final int MAX_DESCRIPTION = 20_000;

    private AlertFormats() {
    }

    static List<Alert> parse(String kind, JsonNode body) {
        if (body == null || !(body.isObject() || body.isArray())) {
            throw invalid("Ожидается JSON-объект");
        }
        return switch (kind) {
            case "zabbix" -> List.of(zabbix(body));
            case "alertmanager" -> alertmanager(body);
            case "generic" -> generic(body);
            default -> throw new IllegalStateException("Неизвестный вид источника " + kind);
        };
    }

    // ---------------------------------------------------------------- Zabbix

    static Alert zabbix(JsonNode b) {
        if (!b.isObject()) {
            throw invalid("Ожидается JSON-объект события Zabbix");
        }
        String eventId = text(b, "event_id");
        String triggerId = text(b, "trigger_id");
        String host = first(text(b, "host"), text(b, "host_name"));
        String key = text(b, "dedup_key");
        if (key == null && triggerId != null) {
            key = "trigger:" + triggerId + (host == null ? "" : ":" + host);
        }
        if (key == null && eventId != null) {
            key = "event:" + eventId;
        }
        if (key == null) {
            throw invalid("Нет event_id и trigger_id");
        }
        String status = upper(text(b, "status"));
        String value = text(b, "value");
        boolean firing = !("RESOLVED".equals(status) || "OK".equals(status) || "0".equals(value));
        String name = first(text(b, "name"), text(b, "subject"));
        if (name == null) {
            throw invalid("Нет названия проблемы (name)");
        }
        Map<String, String> details = new LinkedHashMap<>();
        put(details, "Узел", host);
        put(details, "Событие Zabbix", eventId);
        put(details, "Триггер", triggerId);
        put(details, "Важность в Zabbix", text(b, "severity"));
        // Время Zabbix — в часовом поясе сервера мониторинга, поэтому оно остаётся строкой как есть.
        put(details, "Начало в Zabbix", text(b, "started_at"));
        tags(b.get("tags"), details);
        String title = host == null || name.contains(host) ? name : host + ": " + name;
        return new Alert(key, firing, Severity.normalize(text(b, "severity")), title, text(b, "message"), host,
                link(text(b, "url")), null, details);
    }

    private static void tags(JsonNode tags, Map<String, String> into) {
        if (tags == null || tags.isNull()) {
            return;
        }
        if (tags.isArray()) {
            for (JsonNode t : tags) {
                String tag = text(t, "tag");
                if (tag != null) {
                    put(into, "Тег " + tag, text(t, "value") == null ? "" : text(t, "value"));
                }
            }
            return;
        }
        // Макрос {EVENT.TAGS}: «a:1, b:2»
        String raw = tags.isString() ? tags.asString() : null;
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String p = part.strip();
            if (p.isEmpty() || p.startsWith("{")) {
                continue;
            }
            int sep = p.indexOf(':');
            put(into, "Тег " + (sep < 0 ? p : p.substring(0, sep).strip()), sep < 0 ? "" : p.substring(sep + 1).strip());
        }
    }

    // ---------------------------------------------------------------- Alertmanager и Grafana

    static List<Alert> alertmanager(JsonNode b) {
        JsonNode alerts = b.get("alerts");
        if (alerts == null || !alerts.isArray()) {
            throw invalid("Нет массива alerts");
        }
        List<Alert> out = new ArrayList<>();
        for (JsonNode a : alerts) {
            Map<String, String> labels = map(a.get("labels"));
            Map<String, String> annotations = map(a.get("annotations"));
            String alertname = labels.get("alertname");
            String host = first(labels.get("instance"), labels.get("host"), labels.get("node"), labels.get("pod"));
            String fingerprint = text(a, "fingerprint");
            if (fingerprint == null) {
                if (labels.isEmpty()) {
                    throw invalid("У алерта нет fingerprint и labels");
                }
                fingerprint = labelsHash(labels);
            }
            String summary = first(annotations.get("summary"), annotations.get("title"));
            String title = summary != null ? summary
                    : (alertname == null ? "Алерт" : alertname) + (host == null ? "" : " — " + host);
            String status = text(a, "status");
            if (status == null) {
                status = text(b, "status");
            }
            boolean firing = !"resolved".equalsIgnoreCase(status);
            Map<String, String> details = new LinkedHashMap<>();
            labels.forEach((k, v) -> put(details, k, v));
            put(details, "Значение", text(a, "valueString"));
            String description = first(annotations.get("description"), annotations.get("message"));
            String link = first(link(text(a, "generatorURL")), link(text(a, "dashboardURL")),
                    link(text(a, "panelURL")), link(annotations.get("runbook_url")));
            put(details, "Инструкция", link(annotations.get("runbook_url")));
            out.add(new Alert(fingerprint, firing, Severity.normalize(first(labels.get("severity"),
                    labels.get("priority"))), title, description, host, link, instant(text(a, "startsAt")), details));
        }
        return out;
    }

    /** Отпечаток по меткам, как у Alertmanager: одни и те же метки — один алерт. */
    static String labelsHash(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(labels).forEach((k, v) -> sb.append(k).append('\u0000').append(v).append('\u0001'));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "labels:" + HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- общий формат

    static List<Alert> generic(JsonNode b) {
        JsonNode items = b.isArray() ? b : b.has("events") ? b.get("events") : null;
        List<Alert> out = new ArrayList<>();
        if (items == null) {
            out.add(genericOne(b));
            return out;
        }
        if (!items.isArray()) {
            throw invalid("events должен быть массивом");
        }
        for (JsonNode e : items) {
            out.add(genericOne(e));
        }
        return out;
    }

    private static Alert genericOne(JsonNode e) {
        if (!e.isObject()) {
            throw invalid("Событие должно быть объектом");
        }
        String fingerprint = text(e, "fingerprint");
        String title = text(e, "title");
        if (fingerprint == null || title == null) {
            throw invalid("Обязательны fingerprint и title");
        }
        String status = text(e, "status");
        boolean firing = status == null || !List.of("resolved", "ok", "closed").contains(status.toLowerCase(Locale.ROOT));
        Map<String, String> details = new LinkedHashMap<>();
        put(details, "Узел", text(e, "host"));
        map(e.get("details")).forEach((k, v) -> put(details, k, v));
        return new Alert(fingerprint, firing, Severity.normalize(text(e, "severity")), title, text(e, "description"),
                text(e, "host"), link(text(e, "url")), instant(text(e, "occurred_at")), details);
    }

    // ---------------------------------------------------------------- общее

    /** Ссылка только http(s) и не длиннее 2000 символов, иначе null: в описание заявки не попадёт javascript:. */
    static String link(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.length() > 2000 || !(lower.startsWith("https://") || lower.startsWith("http://"))
                || s.chars().anyMatch(c -> c <= ' ')) {
            return null;
        }
        return s;
    }

    private static Map<String, String> map(JsonNode n) {
        Map<String, String> m = new LinkedHashMap<>();
        if (n == null || !n.isObject()) {
            return m;
        }
        for (Map.Entry<String, JsonNode> e : n.properties()) {
            JsonNode v = e.getValue();
            if (v != null && !v.isNull() && !v.isContainer()) {
                m.put(e.getKey(), v.asString());
            }
        }
        return m;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (key != null && value != null && m.size() < 50) {
            m.put(cut(key, 100), cut(value, 500));
        }
    }

    static String text(JsonNode n, String field) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.isContainer()) {
            return null;
        }
        String s = v.asString().strip();
        // Нераскрытый макрос Zabbix ({HOST.NAME}) — значения нет.
        return s.isEmpty() || (s.startsWith("{") && s.endsWith("}") && !s.contains(" ")) ? null : s;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Instant instant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            Instant i = Instant.parse(raw);
            // Alertmanager шлёт нулевое время у неизвестного значения.
            return i.getEpochSecond() <= 0 ? null : i;
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    static String cut(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_event", detail);
    }
}
