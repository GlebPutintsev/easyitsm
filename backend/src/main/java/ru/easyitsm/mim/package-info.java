/**
 * Крупные инциденты (MIM-01…05, 4.3 и 10.9 ТЗ): объявление одной кнопкой, командный центр, оповещение дежурных
 * по цепочке, сообщения по шаблонам и страница статуса, разбор (PIR) и проблема после закрытия.
 * <p>
 * Крупный инцидент — надстройка над заявкой-инцидентом модуля tickets: приоритет P1, дочерние инциденты связью
 * {@code parent_of}, обновления пользователям — публичные записи в ленты (INC-09). Модуль зависит от
 * {@code tickets.api}, {@code identity.api} (роли менеджера, участники групп), {@code admin.api.DutyRoster}
 * (кто дежурит), {@code notifications.api.Notifier}, {@code problems.api} (проблема из разбора),
 * {@code catalog.api} (имена услуг) и {@code announcements.api} (объявления на странице статуса).
 * <p>
 * Каналы оповещения не зашиты: модуль сам пишет в центр уведомлений и почту, а события
 * {@code OnCallPaged} и {@code MajorIncidentUpdatePublished} слушают будущие каналы (мессенджеры, SMS, MIM-06).
 * REST: {@code /api/v1/major-incidents}, {@code /api/v1/mim/templates}, {@code /api/v1/mim/chains},
 * {@code /api/v1/status}. Контракт — {@code docs/api/mim-contract.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Major incidents")
package ru.easyitsm.mim;
