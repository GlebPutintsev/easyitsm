package ru.easyitsm.licensing.internal;

import java.time.Instant;
import java.util.List;

/** Содержимое лицензии, подпись которой проверена. */
record VerifiedLicense(String licenseId, String licensee, Instant issuedAt, Instant notBefore, Instant notAfter,
                       int maxAgents, List<String> features, String keyId) {
}
