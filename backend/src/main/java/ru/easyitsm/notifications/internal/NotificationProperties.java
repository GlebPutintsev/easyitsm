package ru.easyitsm.notifications.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры уведомлений ({@code easyitsm.notifications}).
 *
 * @param publicUrl   адрес интерфейса для ссылок в письмах и push
 * @param productName название в подписи писем
 * @param pushEnabled отправлять Web Push (в закрытом контуре без доступа к push-сервисам браузеров — выключить)
 * @param pushSubject контакт для push-сервисов (VAPID {@code sub}), mailto: или https:
 */
@ConfigurationProperties("easyitsm.notifications")
record NotificationProperties(String publicUrl, String productName, Boolean pushEnabled, String pushSubject) {

    NotificationProperties {
        publicUrl = publicUrl == null || publicUrl.isBlank() ? "http://localhost:5173" : publicUrl.replaceAll("/+$", "");
        productName = productName == null || productName.isBlank() ? "Easy ITSM" : productName;
        pushEnabled = pushEnabled == null || pushEnabled;
        pushSubject = pushSubject == null || pushSubject.isBlank() ? "mailto:support@easyitsm.local" : pushSubject;
    }

    String url(String path) {
        return path == null ? null : path.startsWith("http") ? path : publicUrl + path;
    }
}
