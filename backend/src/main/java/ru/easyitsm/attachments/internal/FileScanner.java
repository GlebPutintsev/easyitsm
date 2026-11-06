package ru.easyitsm.attachments.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.attachments.events.AttachmentAdded;
import ru.easyitsm.attachments.events.AttachmentQuarantined;

/**
 * Проверка файла из карантина (SEC-25…27): размер, тип по содержимому, исполняемые файлы, архивы, антивирус.
 * Чистый файл переносится в корзину файлов, остальные остаются в карантине. Если антивирус недоступен,
 * файл ждёт в карантине и проверяется повторно («Режимы деградации»: новые файлы остаются в карантине).
 */
@Component
class FileScanner {

    private static final Logger log = LoggerFactory.getLogger(FileScanner.class);

    /** Итог проверки содержимого до антивируса. */
    record Verdict(String status, String reason) {
    }

    private final AttachmentStore store;
    private final ObjectStorage storage;
    private final AttachmentProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Set<String> blockedExtensions;
    private final Clock clock;

    FileScanner(AttachmentStore store, ObjectStorage storage, AttachmentProperties props,
                ApplicationEventPublisher events, TransactionTemplate tx, Optional<Clock> clock) {
        this.store = store;
        this.storage = storage;
        this.props = props;
        this.events = events;
        this.tx = tx;
        this.blockedExtensions = props.blockedExtensions().stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.clock = clock.orElse(Clock.systemUTC());
        if ("none".equals(props.antivirus().mode())) {
            log.warn("Антивирус выключен (easyitsm.attachments.antivirus.mode=none): только для разработки");
        }
    }

    /** Проверяет один файл в арендаторе потока. */
    void scan(UUID id) {
        AttachmentRow a = store.findAny(id).orElse(null);
        if (a == null || !AttachmentRow.SCANNING.equals(a.status())) {
            return;
        }
        if (a.deletedAt() != null) {
            storage.deleteQuarantine(a.storageKey());
            tx.executeWithoutResult(s -> store.finishScan(a.id(), AttachmentRow.BLOCKED, a.contentType(), null,
                    "none", "deleted", clock.instant()));
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("eitsm-scan-", ".bin");
            long size = storage.downloadQuarantine(a.storageKey(), tmp, props.maxFileSize().toBytes());
            String sha256 = sha256(tmp);
            byte[] head = head(tmp);
            ContentSniffer.Kind kind = ContentSniffer.sniff(head, a.fileName());
            Verdict policy = policy(a, size, kind, tmp);
            if (policy != null) {
                finish(a, policy.status(), kind.mime(), sha256, "policy", policy.reason());
                return;
            }
            String engine = props.antivirus().mode();
            String verdict = null;
            if ("clamd".equals(engine)) {
                ClamdClient.Result r = new ClamdClient(props.antivirus()).scan(tmp);
                if (r.infected()) {
                    finish(a, AttachmentRow.INFECTED, kind.mime(), sha256, "clamav", r.signature());
                    return;
                }
            } else {
                verdict = "not_scanned";
            }
            storage.release(a.storageKey(), a.cleanKey());
            finish(a, AttachmentRow.CLEAN, kind.mime(), sha256, engine, verdict);
        } catch (IOException | ObjectStorage.StorageUnavailable e) {
            int attempts = a.scanAttempts();
            if (attempts >= props.antivirus().maxAttempts()) {
                log.error("Файл {} не проверен за {} попыток, остаётся в карантине: {}", a.id(), attempts,
                        e.getMessage());
                finish(a, AttachmentRow.BLOCKED, a.contentType(), null, "none", "scan_failed");
            } else {
                log.warn("Проверка файла {} отложена (попытка {}): {}", a.id(), attempts, e.getMessage());
                tx.executeWithoutResult(s -> store.retryScan(a.id(), attempts, "scan_pending: " + e.getMessage(),
                        clock.instant()));
            }
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // временный каталог очищается при перезапуске пода
                }
            }
        }
    }

    /** Правила до антивируса. null — можно передавать антивирусу. */
    Verdict policy(AttachmentRow a, long size, ContentSniffer.Kind kind, Path file) throws IOException {
        if (size != a.sizeBytes() || size > props.maxFileSize().toBytes()) {
            return new Verdict(AttachmentRow.BLOCKED, "size_mismatch");
        }
        String ext = ContentSniffer.extension(a.fileName());
        if (blockedExtensions.contains(ext)) {
            return new Verdict(AttachmentRow.BLOCKED, "blocked_extension:" + ext);
        }
        if (kind.executable()) {
            return new Verdict(AttachmentRow.BLOCKED, "executable:" + kind.mime());
        }
        if (kind.zipContainer()) {
            ArchiveInspector.Violation v = new ArchiveInspector(props.archives(), blockedExtensions).inspect(file);
            if (v != null) {
                return new Verdict(AttachmentRow.BLOCKED, v.reason());
            }
        }
        return null;
    }

    private void finish(AttachmentRow a, String status, String contentType, String sha256, String engine,
                        String verdict) {
        tx.executeWithoutResult(s -> {
            store.finishScan(a.id(), status, contentType, sha256, engine, verdict, clock.instant());
            if (AttachmentRow.CLEAN.equals(status)) {
                events.publishEvent(new AttachmentAdded(a.tenantId(), a.id(), a.ownerType(), a.ownerId(),
                        a.visibility(), a.fileName(), a.sizeBytes(), a.createdBy()));
            } else {
                events.publishEvent(new AttachmentQuarantined(a.tenantId(), a.id(), a.ownerType(), a.ownerId(),
                        a.fileName(), status, verdict, a.createdBy()));
            }
        });
        if (!AttachmentRow.CLEAN.equals(status)) {
            log.warn("Файл {} ({}) в карантине: {} {}", a.id(), a.fileName(), status, verdict);
        }
    }

    private static byte[] head(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(8192);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
