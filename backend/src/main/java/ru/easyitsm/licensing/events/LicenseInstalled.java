package ru.easyitsm.licensing.events;

import java.time.Instant;
import java.util.UUID;

/** Администратор установил новый файл лицензии. Слушает аудит (SEC-29: изменения настроек и ключей). */
public record LicenseInstalled(String licenseId, String licensee, int maxAgents, Instant notAfter,
                               UUID installedBy, Instant installedAt) {
}
