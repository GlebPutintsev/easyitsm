package ru.easyitsm.cmdb.api;

import java.util.UUID;

/**
 * Краткие сведения о КЕ для других модулей: карточки заявок, изменений, активов, экспорт плана.
 *
 * @param classCode   код класса, например {@code virtual_machine}
 * @param environment код среды: {@code production}, {@code staging}, {@code test}, {@code development} или свой
 * @param version     версия записи, совпадает с ETag в REST
 */
public record CiRef(
        UUID id,
        String name,
        String classCode,
        String className,
        CiLayer layer,
        CiStatus status,
        String environment,
        CiCriticality criticality,
        UUID ownerGroupId,
        long version) {

    /** Бизнес- или техническая услуга. */
    public boolean isService() {
        return layer.isService();
    }
}
