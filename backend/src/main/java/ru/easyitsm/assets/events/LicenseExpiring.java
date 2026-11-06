package ru.easyitsm.assets.events;

import java.time.LocalDate;
import java.util.UUID;

/** Подходит срок действия лицензии (AST-03). Публикуется один раз на каждую дату окончания. */
public record LicenseExpiring(UUID tenantId, UUID licenseId, String product, LocalDate validTo, int daysLeft,
                              UUID ownerId) {
}
