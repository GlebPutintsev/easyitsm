package ru.easyitsm.mail.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры канала почты ({@code easyitsm.mail}).
 *
 * @param secretKey      ключ шифрования паролей ящиков (Base64, 32 байта); по умолчанию ключ модуля identity
 * @param pollInterval   как часто забирать почту из IMAP и Graph
 * @param deliveryInterval как часто отправлять очередь
 * @param retryDelays    паузы между попытками отправки; после последней письмо получает статус failed
 * @param maxPerSenderPerHour больше писем от одного адреса за час — петля или рассылка, письма отклоняются
 * @param maxMessageSize предельный размер входящего письма, байт
 * @param fallback       отправка по умолчанию, если арендатор не настроил свою (установка с одним арендатором,
 *                       Mailpit в разработке); пустой host — отправки по умолчанию нет
 * @param inboundSmtp    встроенный сервер входящего SMTP
 * @param graphLoginUrl  адрес входа Microsoft Entra (для национальных облаков и тестов)
 * @param graphApiUrl    адрес Microsoft Graph
 */
@ConfigurationProperties("easyitsm.mail")
record MailProperties(
        String secretKey,
        Duration pollInterval,
        Duration deliveryInterval,
        List<Duration> retryDelays,
        Integer maxPerSenderPerHour,
        Integer maxMessageSize,
        Fallback fallback,
        InboundSmtp inboundSmtp,
        String graphLoginUrl,
        String graphApiUrl) {

    MailProperties {
        pollInterval = pollInterval == null ? Duration.ofSeconds(60) : pollInterval;
        deliveryInterval = deliveryInterval == null ? Duration.ofSeconds(10) : deliveryInterval;
        retryDelays = retryDelays == null || retryDelays.isEmpty()
                ? List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
                        Duration.ofHours(6))
                : List.copyOf(retryDelays);
        maxPerSenderPerHour = maxPerSenderPerHour == null ? 30 : maxPerSenderPerHour;
        maxMessageSize = maxMessageSize == null ? 25 * 1024 * 1024 : maxMessageSize;
        fallback = fallback == null ? new Fallback(null, null, null, null, null, null, null) : fallback;
        inboundSmtp = inboundSmtp == null ? new InboundSmtp(false, null, null, null) : inboundSmtp;
        graphLoginUrl = graphLoginUrl == null || graphLoginUrl.isBlank()
                ? "https://login.microsoftonline.com" : graphLoginUrl.replaceAll("/+$", "");
        graphApiUrl = graphApiUrl == null || graphApiUrl.isBlank()
                ? "https://graph.microsoft.com/v1.0" : graphApiUrl.replaceAll("/+$", "");
    }

    /** SMTP по умолчанию для всех арендаторов без своей настройки. */
    record Fallback(String host, Integer port, String security, String username, String password,
                    String fromAddress, String fromName) {

        boolean configured() {
            return host != null && !host.isBlank() && fromAddress != null && !fromAddress.isBlank();
        }
    }

    /** Входящий SMTP: порт слушает только роль worker. Арендатор определяется по адресу получателя. */
    record InboundSmtp(boolean enabled, Integer port, String bindAddress, String hostName) {

        int portOrDefault() {
            return port == null ? 2525 : port;
        }
    }
}
