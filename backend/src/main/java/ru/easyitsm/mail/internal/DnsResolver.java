package ru.easyitsm.mail.internal;

import java.util.List;

/** DNS для проверки SPF, DKIM и DMARC. Тесты подставляют свою таблицу записей. */
interface DnsResolver {

    /** TXT-записи; несколько строк одной записи склеиваются. Нет записи — пустой список. */
    List<String> txt(String name) throws DnsException;

    /** Адреса A и AAAA в текстовом виде. */
    List<String> addresses(String name) throws DnsException;

    /** Имена почтовых серверов MX по приоритету. */
    List<String> mx(String name) throws DnsException;

    /** Временный сбой DNS (temperror): письмо не отклоняется по такой проверке. */
    class DnsException extends Exception {
        DnsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
