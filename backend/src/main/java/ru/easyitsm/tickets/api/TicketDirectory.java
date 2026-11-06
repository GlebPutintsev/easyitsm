package ru.easyitsm.tickets.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Справочные данные модуля заявок для экранов поддержки: категории и загрузка исполнителей.
 * Читает в пределах арендатора (RLS), права пользователя проверяет вызывающий модуль.
 */
public interface TicketDirectory {

    /** Категории заявок по имени, включая выключенные (чтобы у старых заявок оставалось имя). */
    List<CategoryRef> categories();

    /**
     * Открытые заявки на исполнителях: всё, что не черновик, не решено, не закрыто и не отменено.
     * Исполнители без заявок в ответ не попадают.
     */
    Map<UUID, Long> openCountByAssignee(Collection<UUID> assigneeIds);

    /** Категория заявки; parentId — для дерева, null у корневых. */
    record CategoryRef(UUID id, UUID parentId, String name, boolean active) {
    }
}
