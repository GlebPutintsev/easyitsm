package ru.easyitsm.changes.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Календарь iCalendar (RFC 5545) для «события в iCal» (10.10): окна изменений и заморозки. */
final class ICal {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private ICal() {
    }

    static byte[] write(List<ChangeDtos.ChangeItem> changes, List<ChangeDtos.FreezeView> freezes, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//Easy ITSM//Changes//RU");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + text("Календарь изменений"));
        Instant now = Instant.now();
        for (ChangeDtos.ChangeItem c : changes) {
            if (c.windowStart() == null || c.windowEnd() == null) {
                continue;
            }
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:" + c.id() + "@easyitsm");
            line(sb, "DTSTAMP:" + UTC.format(now));
            line(sb, "DTSTART:" + UTC.format(c.windowStart()));
            line(sb, "DTEND:" + UTC.format(c.windowEnd()));
            line(sb, "SUMMARY:" + text(c.number() + " " + c.title()));
            line(sb, "DESCRIPTION:" + text("Статус: " + c.state() + (c.riskLevel() == null ? "" : ". Риск: " + c.riskLevel())));
            if (baseUrl != null) {
                line(sb, "URL:" + baseUrl + "/agent/changes/" + c.id());
            }
            line(sb, "STATUS:" + ("cancelled".equals(c.state()) || "rejected".equals(c.state()) ? "CANCELLED"
                    : "scheduled".equals(c.state()) || "implementation".equals(c.state()) ? "CONFIRMED" : "TENTATIVE"));
            line(sb, "CATEGORIES:" + text("Изменение"));
            line(sb, "END:VEVENT");
        }
        for (ChangeDtos.FreezeView f : freezes) {
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:freeze-" + f.id() + "@easyitsm");
            line(sb, "DTSTAMP:" + UTC.format(now));
            line(sb, "DTSTART:" + UTC.format(f.startsAt()));
            line(sb, "DTEND:" + UTC.format(f.endsAt()));
            line(sb, "SUMMARY:" + text("Заморозка изменений: " + f.title()));
            if (f.reason() != null && !f.reason().isBlank()) {
                line(sb, "DESCRIPTION:" + text(f.reason()));
            }
            line(sb, "TRANSP:TRANSPARENT");
            line(sb, "CATEGORIES:" + text("Заморозка"));
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Экранирование TEXT: обратная косая, точка с запятой, запятая, перевод строки. */
    static String text(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "");
    }

    /** Строка со сгибом по 75 октетов UTF-8 (RFC 5545, 3.1), без разрыва символа. */
    static void line(StringBuilder sb, String value) {
        int octets = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int len = ch.getBytes(StandardCharsets.UTF_8).length;
            if (octets + len > 75) {
                sb.append(current).append("\r\n ");
                current.setLength(0);
                octets = 1;
            }
            current.append(ch);
            octets += len;
            i += Character.charCount(cp);
        }
        sb.append(current).append("\r\n");
    }
}
