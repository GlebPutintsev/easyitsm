package ru.easyitsm.automation.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки защиты от петель и журнала ({@code easyitsm.automation.*}).
 *
 * @param maxDepth      предельная глубина цепочки правил (AUT-04: 5)
 * @param rateLimit     сколько раз одно правило может выполнить действия на одной заявке за {@code rateWindow}
 * @param rateWindow    окно ограничения частоты
 * @param keepRunsDays  сколько дней хранить журнал запусков
 */
@ConfigurationProperties("easyitsm.automation")
record AutomationProperties(Integer maxDepth, Integer rateLimit, Duration rateWindow, Integer keepRunsDays) {

    AutomationProperties {
        maxDepth = maxDepth == null ? 5 : maxDepth;
        rateLimit = rateLimit == null ? 10 : rateLimit;
        rateWindow = rateWindow == null ? Duration.ofMinutes(5) : rateWindow;
        keepRunsDays = keepRunsDays == null ? 90 : keepRunsDays;
    }
}
