package ru.easyitsm.assets.api;

import java.util.List;
import java.util.UUID;

/**
 * Чтение активов другими модулями в арендаторе вызывающего (RLS), без проверки прав пользователя:
 * вызывающий сам решает, кому показать результат.
 */
public interface AssetQueries {

    /** Оборудование, выданное сотруднику или числящееся за ним в ремонте (AST-05). */
    List<AssetSummary> heldBy(UUID userId);

    /** Активы, связанные с КЕ (AST-05). */
    List<AssetSummary> linkedToCi(UUID ciId);

    /** Активы, связанные с заявкой (AST-05). */
    List<AssetSummary> linkedToTicket(UUID ticketId);
}
