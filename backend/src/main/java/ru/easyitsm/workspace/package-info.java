/**
 * Рабочее место агента (раздел 10.2 ТЗ): серверная часть экрана, которая собирает данные других модулей
 * в один ответ. Своих таблиц нет.
 * <p>
 * REST: {@code GET /api/v1/agent-workspace/directory} — справочник имён для очереди и карточки заявки:
 * группы и агенты из identity, услуги из catalog, категории и загрузка из tickets, коды причин.
 * Имена прочих людей (заявителей, наблюдателей) фронтенд берёт пакетно из {@code GET /api/v1/people?ids=}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Agent workspace")
package ru.easyitsm.workspace;
