package ru.easyitsm.changes.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Изменение, которое создаёт другой модуль: проблема (PRB-04), инцидент, CI/CD. Создаётся черновиком; шаблон
 * подставляет тексты и шаги (CHG-08). Права вызывающий модуль проверяет сам.
 *
 * @param templateId     шаблон, null — без шаблона; тип берётся из шаблона, если {@code type} null
 * @param sourceTicketId заявка, из которой создано изменение (связь «связана с»)
 */
public record NewChange(
        ChangeType type,
        String title,
        String description,
        UUID authorId,
        UUID templateId,
        UUID sourceTicketId,
        List<UUID> ciIds,
        List<UUID> serviceIds) {

    public NewChange {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(authorId, "authorId");
        ciIds = ciIds == null ? List.of() : List.copyOf(ciIds);
        serviceIds = serviceIds == null ? List.of() : List.copyOf(serviceIds);
    }
}
