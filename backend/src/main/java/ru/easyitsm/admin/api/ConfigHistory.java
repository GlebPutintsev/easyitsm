package ru.easyitsm.admin.api;

import java.util.UUID;

/**
 * Версии настроек (ADM-06, 7.6 {@code config_version}). Модуль, который сохраняет настройку, может записать её
 * снимок сам; экраны «Управления» записывают версии настроек других модулей через REST
 * {@code POST /api/v1/admin/config-versions}. Автор берётся из текущего пользователя, номер версии растёт
 * в пределах (entity, entityId).
 */
public interface ConfigHistory {

    /**
     * @param entity   вид настройки {@code <модуль>.<объект>}, например {@code tickets.priority_matrix}
     * @param entityId id объекта или пустая строка для настройки в одном экземпляре
     * @param action   create, update, delete, restore, import, rollback или setup
     * @param snapshot снимок, сериализуемый в JSON
     * @return id версии
     */
    UUID record(String entity, String entityId, String label, String action, Object snapshot, String comment);
}
