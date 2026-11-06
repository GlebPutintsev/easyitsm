package ru.easyitsm.integrations.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки приёма событий ({@code easyitsm.integrations.*}).
 *
 * @param maxBodyBytes       наибольший размер запроса мониторинга, больше — 413
 * @param maxAlertsPerEvent  алертов в одном запросе (группа Alertmanager), лишние пропускаются с отметкой в журнале
 * @param ratePerMinute      запросов в минуту от одного источника, больше — 429 (защита от шторма алертов)
 * @param keepEventsDays     сколько дней хранить журнал событий и доставок вебхуков
 * @param outbound           исходящие запросы: вебхуки
 */
@ConfigurationProperties("easyitsm.integrations")
record IntegrationsProperties(Integer maxBodyBytes, Integer maxAlertsPerEvent, Integer ratePerMinute,
                              Integer keepEventsDays, Outbound outbound) {

    /**
     * @param allowLoopback разрешить адреса этого сервера (127.0.0.1) — только тесты и стенд разработчика
     * @param timeout       ожидание ответа получателя
     * @param maxAttempts   попыток доставки, затем — в неудачные (раздел 8.3: 8 повторов)
     */
    record Outbound(Boolean allowLoopback, Duration timeout, Integer maxAttempts) {

        Outbound {
            allowLoopback = Boolean.TRUE.equals(allowLoopback);
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
            maxAttempts = maxAttempts == null ? 9 : maxAttempts;
        }
    }

    IntegrationsProperties {
        maxBodyBytes = maxBodyBytes == null ? 1_048_576 : maxBodyBytes;
        maxAlertsPerEvent = maxAlertsPerEvent == null ? 200 : maxAlertsPerEvent;
        ratePerMinute = ratePerMinute == null ? 600 : ratePerMinute;
        keepEventsDays = keepEventsDays == null ? 30 : keepEventsDays;
        outbound = outbound == null ? new Outbound(null, null, null) : outbound;
    }
}
