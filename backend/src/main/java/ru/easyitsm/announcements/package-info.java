/**
 * Объявления портала (PRT-01): плановые работы и сбои.
 * <p>
 * <b>Эталонный модуль.</b> Новый модуль повторяет его устройство:
 * <ul>
 *   <li>{@code api} — публичные интерфейсы и DTO, которыми пользуются другие модули;</li>
 *   <li>{@code events} — доменные события модуля, их слушают другие модули через outbox;</li>
 *   <li>{@code internal} — сущности, репозитории, сервисы и контроллеры, недоступные снаружи;</li>
 *   <li>миграции в {@code db/migration/announcements/}, таблицы с {@code tenant_id} и RLS.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Announcements")
package ru.easyitsm.announcements;
