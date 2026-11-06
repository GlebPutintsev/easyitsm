package ru.easyitsm.integrations.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки приёма событий ({@code easyitsm.integrations.*}).
 *
 * @param maxBodyBytes       наибольший размер запроса мониторинга, больше — 413
 * @param maxAlertsPerEvent  алертов в одном запросе (группа Alertmanager), лишние пропускаются с отметкой в журнале
 * @param ratePerMinute      запросов в минуту от одного источника, больше — 429 (защита от шторма алертов)
 * @param keepEventsDays     сколько дней хранить журнал событий
 */
@ConfigurationProperties("easyitsm.integrations")
record IntegrationsProperties(Integer maxBodyBytes, Integer maxAlertsPerEvent, Integer ratePerMinute,
                              Integer keepEventsDays) {

    IntegrationsProperties {
        maxBodyBytes = maxBodyBytes == null ? 1_048_576 : maxBodyBytes;
        maxAlertsPerEvent = maxAlertsPerEvent == null ? 200 : maxAlertsPerEvent;
        ratePerMinute = ratePerMinute == null ? 600 : ratePerMinute;
        keepEventsDays = keepEventsDays == null ? 30 : keepEventsDays;
    }
}
