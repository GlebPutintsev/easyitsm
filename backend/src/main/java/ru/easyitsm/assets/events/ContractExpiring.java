package ru.easyitsm.assets.events;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Подходит срок окончания договора ({@code contract.expiring}, AST-04): наступил срок напоминания о продлении.
 * Публикуется один раз на каждую дату окончания.
 *
 * @param ownerId ответственный за договор, null — не назначен
 */
public record ContractExpiring(UUID tenantId, UUID contractId, String number, String title, LocalDate endsOn,
                               int daysLeft, boolean autoRenew, UUID ownerId) {
}
