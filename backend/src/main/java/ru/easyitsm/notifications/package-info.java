/**
 * Уведомления (NTF-01, NTF-02, NTF-05): правила по событиям заявок, SLA, согласований и входа, шаблоны на русском
 * и английском с предпросмотром, центр уведомлений в интерфейсе, почта через очередь модуля mail и Web Push.
 * <p>
 * Контракт для других модулей:
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.notifications.api.Notifier}: уведомить пользователя (действие
 *       автоматизации, сообщение модуля);</li>
 *   <li>REST: {@code /api/v1/notifications/**} — центр уведомлений и подписки Web Push текущего пользователя,
 *       {@code /api/v1/notification-settings/**} — шаблоны (ADM).</li>
 * </ul>
 * Конфиденциальное содержимое (ACC-04) в письма и push не попадает: модель шаблона маскирует его сама,
 * поэтому шаблон администратора не может его раскрыть (NTF-05).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications")
package ru.easyitsm.notifications;
