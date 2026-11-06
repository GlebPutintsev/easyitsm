package ru.easyitsm.audit.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DTO REST журнала аудита. */
final class AuditViews {

    private AuditViews() {
    }

    record Event(UUID id, long seq, Instant at, String category, String action, String result, UUID actorId,
                 String actorLogin, String objectType, String objectId, String ip, String userAgent, String traceId,
                 Map<String, Object> details, String prevHash, String hash) {
    }

    /**
     * Фильтр списка и выгрузки. {@code action} — точный код или префикс с точкой на конце ({@code auth.}),
     * {@code q} — подстрока в действии, объекте, логине, адресе и деталях.
     */
    record Filter(Instant from, Instant to, String category, String action, String result, UUID actorId,
                  String objectType, String objectId, String q) {

        static Filter empty() {
            return new Filter(null, null, null, null, null, null, null, null, null);
        }

        Map<String, Object> describe() {
            var out = new java.util.LinkedHashMap<String, Object>();
            if (from != null) out.put("from", from);
            if (to != null) out.put("to", to);
            if (category != null) out.put("category", category);
            if (action != null) out.put("action", action);
            if (result != null) out.put("result", result);
            if (actorId != null) out.put("actorId", actorId);
            if (objectType != null) out.put("objectType", objectType);
            if (objectId != null) out.put("objectId", objectId);
            if (q != null) out.put("q", q);
            return out;
        }
    }

    /**
     * Итог проверки целостности.
     *
     * @param problem null, если цепочка цела; иначе hash_mismatch (запись изменена), link_mismatch (нарушена
     *                связь с предыдущей), gap (пропущен номер — запись удалена), truncated (удалён хвост)
     */
    record Verification(boolean valid, long checked, long headSeq, String headHash, Long brokenAtSeq, String problem,
                        Instant verifiedAt) {
    }

    record ExportRequest(String reason, Instant from, Instant to, String category, String action, String result,
                         UUID actorId, String objectType, String objectId, String q) {

        Filter filter() {
            return new Filter(from, to, category, action, result, actorId, objectType, objectId, q);
        }
    }
}
