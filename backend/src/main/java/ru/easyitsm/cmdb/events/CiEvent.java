package ru.easyitsm.cmdb.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiRef;

/**
 * Общие поля событий КЕ. {@code actorId} — null для системы и загрузки из источника, {@code source} — код
 * источника данных ({@code manual} для ручного изменения), {@code ci} — снимок после изменения.
 */
public interface CiEvent {

    UUID tenantId();

    Instant occurredAt();

    UUID actorId();

    String source();

    CiRef ci();

    default UUID ciId() {
        return ci().id();
    }
}
