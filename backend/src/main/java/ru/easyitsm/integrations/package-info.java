/**
 * Интеграции (раздел 8 ТЗ): приём событий мониторинга — Zabbix, Prometheus Alertmanager, Grafana и общий JSON.
 * <p>
 * Источник мониторинга шлёт события на {@code POST /api/v1/integrations/events} со своим токеном
 * ({@code Authorization: Bearer …}). Один отпечаток активного алерта — один инцидент: повторы только
 * увеличивают счётчик, рост важности поднимает влияние и срочность, повтор после решения возвращает инцидент
 * в работу, а «проблема устранена» решает его (INC-01, автосоздание и автозакрытие из 8.4).
 * <p>
 * Модуль действует через {@code tickets.api} от имени служебного пользователя источника и не публикует своих
 * событий. REST настроек: {@code /api/v1/integrations/monitoring/**} (ADM, чтение — ещё AUD).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Integrations")
package ru.easyitsm.integrations;
