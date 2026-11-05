/**
 * Ядро платформы: арендатор и RLS, идентификаторы, базовая сущность, ошибки API,
 * постраничность, идемпотентность, ETag, контекст текущего пользователя.
 * <p>
 * Модуль открыт: его типы может использовать любой модуль. Сюда попадает только то,
 * что нужно всем модулям. Доменная логика сюда не добавляется.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Platform kernel",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package ru.easyitsm.platform;
