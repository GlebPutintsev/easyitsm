package ru.easyitsm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Форматы SIEM: Syslog RFC 5424 в рамке с длиной, CEF с экранированием, JSON. */
class SiemFormatterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AuditViews.Event event = new AuditViews.Event(
            UUID.fromString("01930000-0000-7000-8000-0000000000aa"), 7, Instant.parse("2026-11-06T08:00:00.123Z"),
            "access", "access.denied", "denied", UUID.fromString("01930000-0000-7000-8000-0000000000bb"),
            "ivan|admin", "tickets/{ref}", "INC=1\\2", "10.0.0.1", "Mozilla\n5.0", "abc",
            Map.of("route", "/api/v1/tickets/{ref}"), "0".repeat(64), "f".repeat(64));

    @Test
    void cefIsEscaped() {
        String cef = new SiemFormatter(AuditProperties.Format.CEF, "easyitsm", "host", "1.0", json)
                .cef(event, "demo");
        assertThat(cef).startsWith("CEF:0|EasyITSM|Easy ITSM|1.0|access.denied|access.denied|5|");
        assertThat(cef).contains("suser=ivan|admin").contains("cs4=INC\\=1\\\\2")
                .contains("requestClientApplication=Mozilla\\n5.0").contains("externalId=01930000-0000-7000-8000-0000000000aa")
                .contains("cs2=demo").contains("cn1=7").contains("rt=1793952000123");
        assertThat(SiemFormatter.header("a|b\\c")).isEqualTo("a\\|b\\\\c");
    }

    @Test
    void syslogFrameHasLengthPrefix() {
        byte[] frame = new SiemFormatter(AuditProperties.Format.JSON, "easy itsm", "my host", "1.0", json)
                .frame(event, "demo");
        String s = new String(frame, StandardCharsets.UTF_8);
        int space = s.indexOf(' ');
        int len = Integer.parseInt(s.substring(0, space));
        assertThat(frame.length - space - 1).isEqualTo(len);
        // facility 13 (log audit) * 8 + warning (4)
        assertThat(s.substring(space + 1)).startsWith("<108>1 2026-11-06T08:00:00.123Z myhost easyitsm - access.denied - {");
        assertThat(s).contains("\"seq\":7").contains("\"tenant\":\"demo\"").contains("\"hash\":\"ffff");
    }

    @Test
    void severity() {
        assertThat(SiemFormatter.cefSeverity(event)).isEqualTo(5);
        var verifyFailed = new AuditViews.Event(event.id(), 1, event.at(), "audit", "audit.verify", "failure", null,
                null, null, null, null, null, null, Map.of(), "", "");
        assertThat(SiemFormatter.cefSeverity(verifyFailed)).isEqualTo(10);
        assertThat(SiemFormatter.syslogSeverity(verifyFailed)).isEqualTo(1);
    }

    @Test
    void csvCellsCannotBecomeFormulas() {
        assertThat(AuditCsv.cell("=1+1")).isEqualTo("\"'=1+1\"");
        assertThat(AuditCsv.cell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
        assertThat(AuditCsv.cell("-2")).isEqualTo("\"'-2\"");
        assertThat(AuditCsv.cell("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(AuditCsv.cell(null)).isEqualTo("\"\"");
    }
}
