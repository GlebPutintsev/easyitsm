package ru.easyitsm.integrations.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Исходящие вебхуки для других модулей (раздел 8.3 ТЗ): автоматизация выбирает получателя и ставит событие
 * в очередь. Доставка — фоновым обработчиком после фиксации транзакции, с подписью HMAC-SHA256 и повторами.
 * Адрес получателя проверяется по списку разрешённых адресов арендатора при каждой отправке (8.5).
 */
public interface Webhooks {

    /** Получатель для выбора в правиле. */
    record Ref(UUID id, String name, boolean enabled) {
    }

    List<Ref> list();

    Optional<Ref> find(UUID webhookId);

    /**
     * Ставит событие в очередь в транзакции и арендаторе вызывающего. Выключенному или удалённому получателю —
     * {@link ru.easyitsm.platform.web.ApiException} {@code webhook_unavailable}.
     *
     * @param eventType     тип события в теле, например {@code automation.rule}
     * @param data          содержимое {@code data}: сериализуется в JSON
     * @param source        откуда событие для журнала доставок, например название правила
     * @param ticketNumber  заявка для журнала доставок, может быть null
     * @return id доставки (он же {@code id} события в теле)
     */
    UUID enqueue(UUID webhookId, String eventType, Map<String, Object> data, String source, String ticketNumber);
}
