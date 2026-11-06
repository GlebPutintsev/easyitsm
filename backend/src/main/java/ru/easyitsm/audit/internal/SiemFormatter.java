package ru.easyitsm.audit.internal;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Запись журнала → сообщение Syslog RFC 5424 с телом CEF или JSON, в рамке с длиной (RFC 5425 / RFC 6587).
 * Объект Facility 13 («log audit»). Детали уже замаскированы при записи в журнал.
 */
final class SiemFormatter {

    private static final int FACILITY_LOG_AUDIT = 13;
    private static final String VENDOR = "EasyITSM";
    private static final String PRODUCT = "Easy ITSM";

    private final AuditProperties.Format format;
    private final String appName;
    private final String hostname;
    private final String version;
    private final ObjectMapper json;

    SiemFormatter(AuditProperties.Format format, String appName, String hostname, String version, ObjectMapper json) {
        this.format = format;
        this.appName = printable(appName, 48);
        this.hostname = printable(hostname, 255);
        this.version = version;
        this.json = json;
    }

    /** Готовая к отправке рамка: «длина пробел сообщение». */
    byte[] frame(AuditViews.Event e, String tenantCode) {
        String body = format == AuditProperties.Format.CEF ? cef(e, tenantCode) : json(e, tenantCode);
        String msg = "<" + (FACILITY_LOG_AUDIT * 8 + syslogSeverity(e)) + ">1 "
                + DateTimeFormatter.ISO_INSTANT.format(e.at()) + " " + hostname + " " + appName + " - "
                + printable(e.action(), 32) + " - " + body;
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = (bytes.length + " ").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(bytes, 0, out, prefix.length, bytes.length);
        return out;
    }

    String cef(AuditViews.Event e, String tenantCode) {
        Map<String, String> ext = new LinkedHashMap<>();
        ext.put("rt", Long.toString(e.at().toEpochMilli()));
        ext.put("cat", e.category());
        ext.put("outcome", e.result());
        put(ext, "suser", e.actorLogin());
        put(ext, "suid", e.actorId());
        put(ext, "src", e.ip());
        put(ext, "requestClientApplication", e.userAgent());
        ext.put("externalId", e.id().toString());
        ext.put("cs1Label", "traceId");
        put(ext, "cs1", e.traceId());
        ext.put("cs2Label", "tenant");
        put(ext, "cs2", tenantCode);
        ext.put("cs3Label", "objectType");
        put(ext, "cs3", e.objectType());
        ext.put("cs4Label", "objectId");
        put(ext, "cs4", e.objectId());
        ext.put("cs5Label", "hash");
        ext.put("cs5", e.hash());
        ext.put("cs6Label", "prevHash");
        ext.put("cs6", e.prevHash());
        ext.put("cn1Label", "seq");
        ext.put("cn1", Long.toString(e.seq()));
        if (!e.details().isEmpty()) {
            ext.put("msg", json.writeValueAsString(e.details()));
        }
        StringBuilder sb = new StringBuilder("CEF:0|").append(header(VENDOR)).append('|').append(header(PRODUCT))
                .append('|').append(header(version)).append('|').append(header(e.action())).append('|')
                .append(header(e.action())).append('|').append(cefSeverity(e)).append('|');
        String sep = "";
        for (var x : ext.entrySet()) {
            sb.append(sep).append(x.getKey()).append('=').append(extension(x.getValue()));
            sep = " ";
        }
        return sb.toString();
    }

    String json(AuditViews.Event e, String tenantCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", 1);
        out.put("id", e.id());
        out.put("tenant", tenantCode);
        out.put("seq", e.seq());
        out.put("at", DateTimeFormatter.ISO_INSTANT.format(e.at()));
        out.put("category", e.category());
        out.put("action", e.action());
        out.put("result", e.result());
        out.put("severity", cefSeverity(e));
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", e.actorId());
        actor.put("login", e.actorLogin());
        out.put("actor", actor);
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", e.objectType());
        object.put("id", e.objectId());
        out.put("object", object);
        out.put("ip", e.ip());
        out.put("userAgent", e.userAgent());
        out.put("traceId", e.traceId());
        out.put("details", e.details());
        out.put("prevHash", e.prevHash());
        out.put("hash", e.hash());
        return json.writeValueAsString(out);
    }

    /** CEF: 0–10. Нарушение целостности — 10, блокировка и отказ — средние, обычные действия — низкие. */
    static int cefSeverity(AuditViews.Event e) {
        String a = e.action();
        if (a.startsWith("audit.integrity") || ("audit.verify".equals(a) && !"success".equals(e.result()))) {
            return 10;
        }
        if (a.equals("auth.account.locked") || a.equals("auth.factor.mfa_reset")
                || a.equals("auth.factor.totp_disabled") || a.equals("auth.factor.webauthn_removed")) {
            return 7;
        }
        if (a.startsWith("access.role.") || a.startsWith("security.") || a.equals("data.export")
                || a.equals("audit.export")) {
            return 6;
        }
        if (!"success".equals(e.result())) {
            return 5;
        }
        return a.startsWith("config.") || a.startsWith("access.") ? 3 : 2;
    }

    /** Syslog: 1 alert, 4 warning, 5 notice, 6 informational. */
    static int syslogSeverity(AuditViews.Event e) {
        int cef = cefSeverity(e);
        if (cef >= 9) {
            return 1;
        }
        if (cef >= 5) {
            return 4;
        }
        return cef >= 3 ? 5 : 6;
    }

    private static void put(Map<String, String> ext, String key, Object value) {
        if (value != null) {
            ext.put(key, value instanceof UUID u ? u.toString() : String.valueOf(value));
        }
    }

    static String header(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    static String extension(String s) {
        return s.replace("\\", "\\\\").replace("=", "\\=").replace("\r\n", "\\n").replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /** Поля заголовка Syslog: печатный ASCII без пробелов, пусто — «-». */
    static String printable(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c > 32 && c < 127 && sb.length() < max) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }
}
