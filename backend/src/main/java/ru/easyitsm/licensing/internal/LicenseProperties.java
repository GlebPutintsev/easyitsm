package ru.easyitsm.licensing.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param file файл лицензии, смонтированный при установке (Helm: {@code license.existingSecret}). Необязателен:
 *             лицензию можно загрузить в «Управлении».
 */
@ConfigurationProperties("easyitsm.license")
record LicenseProperties(Path file) {
}
