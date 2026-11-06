package ru.easyitsm.integrations.internal;

import java.time.Instant;
import java.util.Map;

/**
 * Одно событие алерта после разбора формата источника.
 *
 * @param fingerprint отпечаток: события с одним отпечатком относятся к одной проблеме
 * @param firing      true — проблема есть, false — устранена
 * @param host        узел или экземпляр, если источник его называет
 * @param link        ссылка на алерт в системе мониторинга (только http/https)
 * @param details     метки и теги для описания инцидента, в порядке источника
 */
record Alert(
        String fingerprint,
        boolean firing,
        Severity severity,
        String title,
        String description,
        String host,
        String link,
        Instant startsAt,
        Map<String, String> details) {

    String status() {
        return firing ? "firing" : "resolved";
    }
}
