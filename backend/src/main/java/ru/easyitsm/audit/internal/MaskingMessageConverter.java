package ru.easyitsm.audit.internal;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ru.easyitsm.audit.api.Masking;

/**
 * {@code %m} в логах приложения с маскированием (SEC-13, раздел 12.5): пароли, токены, ключи, номера карт
 * и адреса почты не попадают в лог, даже если их случайно передали в сообщение. Подключается в
 * {@code logback-spring.xml}. Должен быть public: его создаёт Logback.
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return Masking.forLog(super.convert(event));
    }
}
