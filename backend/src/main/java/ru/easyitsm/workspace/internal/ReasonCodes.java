package ru.easyitsm.workspace.internal;

import java.util.List;
import ru.easyitsm.workspace.internal.AgentDirectoryController.Ref;

/**
 * Коды решения, приостановки и отмены, которые агент выбирает в диалогах перехода. Модуль заявок хранит код
 * как строку и не проверяет его по списку, поэтому список пока задан здесь; настройку в «Управлении»
 * добавит модуль управления (волна 2), коды при этом должны остаться прежними.
 */
final class ReasonCodes {

    private ReasonCodes() {
    }

    static final List<Ref> RESOLUTION = List.of(
            new Ref("fixed", "Устранено"),
            new Ref("workaround", "Обходное решение"),
            new Ref("consultation", "Консультация"),
            new Ref("no_repro", "Не воспроизводится"),
            new Ref("duplicate", "Дубликат"));

    static final List<Ref> PENDING = List.of(
            new Ref("requester", "Ожидание ответа заявителя"),
            new Ref("vendor", "Ожидание поставщика"),
            new Ref("change", "Ожидание изменения"),
            new Ref("delivery", "Ожидание поставки оборудования"));

    static final List<Ref> CANCEL = List.of(
            new Ref("duplicate", "Дубликат"),
            new Ref("requester", "Отозвана заявителем"),
            new Ref("mistake", "Создана по ошибке"));
}
