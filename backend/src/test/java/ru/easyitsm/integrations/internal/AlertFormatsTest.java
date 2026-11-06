package ru.easyitsm.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.web.ApiException;
import tools.jackson.databind.json.JsonMapper;

/** Разбор форматов Zabbix, Alertmanager/Grafana и общего JSON без базы. */
class AlertFormatsTest {

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void zabbixProblemAndRecoveryShareFingerprintByTrigger() {
        Alert problem = AlertFormats.zabbix(json.readTree("""
                {"event_id":"101","trigger_id":"77","status":"PROBLEM","severity":"Disaster",
                 "name":"Нет связи","host":"db-1","message":"ping loss 100%","url":"https://zbx.local/tr_events.php?eventid=101",
                 "tags":"service:db, env:prod","started_at":"2026.11.06 10:15:00"}"""));
        Alert recovery = AlertFormats.zabbix(json.readTree("""
                {"event_id":"101","trigger_id":"77","status":"RESOLVED","value":"0","severity":"Disaster",
                 "name":"Нет связи","host":"db-1"}"""));
        assertThat(problem.firing()).isTrue();
        assertThat(recovery.firing()).isFalse();
        assertThat(problem.fingerprint()).isEqualTo("trigger:77:db-1").isEqualTo(recovery.fingerprint());
        assertThat(problem.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(problem.title()).isEqualTo("db-1: Нет связи");
        assertThat(problem.details()).containsEntry("Тег service", "db").containsEntry("Тег env", "prod");
        assertThat(problem.details()).containsEntry("Начало в Zabbix", "2026.11.06 10:15:00");
        assertThat(problem.link()).startsWith("https://zbx.local/");
    }

    @Test
    void zabbixUnexpandedMacrosAreIgnored() {
        Alert a = AlertFormats.zabbix(json.readTree("""
                {"event_id":"5","trigger_id":"{TRIGGER.ID}","status":"PROBLEM","severity":"Average","name":"CPU",
                 "host":"{HOST.NAME}","tags":"{EVENT.TAGS}"}"""));
        assertThat(a.fingerprint()).isEqualTo("event:5");
        assertThat(a.host()).isNull();
        assertThat(a.severity()).isEqualTo(Severity.WARNING);
        assertThat(a.details()).doesNotContainKey("Узел");
    }

    @Test
    void alertmanagerGroupBecomesSeparateAlertsAndFallsBackToLabelsHash() {
        List<Alert> alerts = AlertFormats.alertmanager(json.readTree("""
                {"version":"4","status":"firing","alerts":[
                  {"status":"firing","labels":{"alertname":"HighLatency","instance":"api:9100","severity":"critical"},
                   "annotations":{"summary":"Задержка API выше 2 с","description":"p99 = 3.1 s"},
                   "startsAt":"2026-11-06T10:00:00Z","endsAt":"0001-01-01T00:00:00Z",
                   "generatorURL":"http://prom.local/graph?g0.expr=up","fingerprint":"abc123"},
                  {"status":"resolved","labels":{"alertname":"DiskFull","instance":"fs:9100","severity":"warning"},
                   "annotations":{},"startsAt":"0001-01-01T00:00:00Z"}]}"""));
        assertThat(alerts).hasSize(2);
        Alert first = alerts.get(0);
        assertThat(first.fingerprint()).isEqualTo("abc123");
        assertThat(first.title()).isEqualTo("Задержка API выше 2 с");
        assertThat(first.description()).isEqualTo("p99 = 3.1 s");
        assertThat(first.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(first.link()).isEqualTo("http://prom.local/graph?g0.expr=up");
        Alert second = alerts.get(1);
        assertThat(second.firing()).isFalse();
        assertThat(second.title()).isEqualTo("DiskFull — fs:9100");
        assertThat(second.startsAt()).isNull();
        assertThat(second.fingerprint()).isEqualTo(AlertFormats.labelsHash(Map.of("alertname", "DiskFull",
                "instance", "fs:9100", "severity", "warning")));
    }

    @Test
    void genericFormatAcceptsSingleAndBatch() {
        assertThat(AlertFormats.generic(json.readTree("""
                {"fingerprint":"x","title":"Очередь растёт","severity":"high"}""")))
                .singleElement().satisfies(a -> {
                    assertThat(a.firing()).isTrue();
                    assertThat(a.severity()).isEqualTo(Severity.HIGH);
                });
        assertThat(AlertFormats.generic(json.readTree("""
                {"events":[{"fingerprint":"a","title":"A","status":"resolved"},{"fingerprint":"b","title":"B"}]}""")))
                .extracting(Alert::firing).containsExactly(false, true);
        assertThatThrownBy(() -> AlertFormats.generic(json.readTree("{\"title\":\"Без отпечатка\"}")))
                .isInstanceOf(ApiException.class).hasMessageContaining("fingerprint");
    }

    @Test
    void onlyHttpLinksAreKept() {
        assertThat(AlertFormats.link("javascript:alert(1)")).isNull();
        assertThat(AlertFormats.link("https://ok.local/a b")).isNull();
        assertThat(AlertFormats.link(" https://ok.local/a ")).isEqualTo("https://ok.local/a");
    }

    @Test
    void severityNamesAreNormalized() {
        assertThat(Severity.normalize("Disaster")).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.normalize("High")).isEqualTo(Severity.HIGH);
        assertThat(Severity.normalize("Average")).isEqualTo(Severity.WARNING);
        assertThat(Severity.normalize("Not classified")).isEqualTo(Severity.INFO);
        assertThat(Severity.normalize("page")).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.normalize(null)).isEqualTo(Severity.WARNING);
        assertThat(Severity.normalize("что-то своё")).isEqualTo(Severity.WARNING);
    }
}
