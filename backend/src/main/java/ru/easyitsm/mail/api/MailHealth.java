package ru.easyitsm.mail.api;

/**
 * Проверка отправки почты текущего арендатора без отправки письма: для мастера первичной настройки (ADM-01).
 * Берёт сохранённый в «Управление → Почта» сервер, иначе сервер установки из переменных окружения.
 */
public interface MailHealth {

    /**
     * @param configured сервер отправки задан
     * @param ok         подключение и вход прошли
     * @param target     «host:port» или «Microsoft Graph»
     */
    record Status(boolean configured, boolean ok, String target, String detail, long latencyMs) {
    }

    Status checkTransport();
}
