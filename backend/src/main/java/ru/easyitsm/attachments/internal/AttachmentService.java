package ru.easyitsm.attachments.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/**
 * Вложения: ссылка на загрузку, подтверждение, список, ссылки на скачивание и просмотр, удаление, карантин.
 * Права — от владельца ({@link OwnerAccess}); невидимый владелец или файл — 404.
 */
@Service
class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentStore store;
    private final OwnerAccess owners;
    private final ObjectStorage storage;
    private final AttachmentProperties props;
    private final Set<String> blockedExtensions;
    private final Clock clock;

    AttachmentService(AttachmentStore store, OwnerAccess owners, ObjectStorage storage, AttachmentProperties props,
                      Optional<Clock> clock) {
        this.store = store;
        this.owners = owners;
        this.storage = storage;
        this.props = props;
        this.blockedExtensions = props.blockedExtensions().stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @Transactional
    AttachmentViews.Created create(AttachmentViews.NewUpload in) {
        UUID me = me();
        String ownerType = in.ownerType().strip().toLowerCase(Locale.ROOT);
        OwnerAccess.Rights rights = owners.rights(ownerType, in.ownerId())
                .orElseThrow(() -> ApiException.notFound("Владелец файла"));
        String visibility = in.visibility() == null || in.visibility().isBlank() ? "public"
                : in.visibility().strip().toLowerCase(Locale.ROOT);
        if (!visibility.equals("public") && !visibility.equals("internal")) {
            throw ApiException.badRequest("invalid_visibility", "visibility: public или internal");
        }
        boolean allowed = visibility.equals("internal") ? rights.canAddInternal() : rights.canAdd();
        if (!allowed) {
            throw ApiException.forbidden();
        }
        String name = cleanName(in.fileName());
        if (in.size() > props.maxFileSize().toBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "Файл больше " + props.maxFileSize().toMegabytes() + " МБ");
        }
        String ext = ContentSniffer.extension(name);
        if (blockedExtensions.contains(ext)) {
            throw ApiException.badRequest("file_type_blocked", "Файлы ." + ext + " не принимаются");
        }
        Instant now = clock.instant();
        if (store.uploadsSince(me, now.minus(Duration.ofMinutes(1))) >= props.uploadsPerMinute()
                || store.uploadsSince(me, now.minus(Duration.ofHours(1))) >= props.uploadsPerHour()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "upload_rate_limited",
                    "Слишком много загрузок, попробуйте позже");
        }
        if (store.countByOwner(ownerType, in.ownerId()) >= props.maxPerOwner()) {
            throw ApiException.conflict("too_many_attachments", "У записи уже " + props.maxPerOwner() + " файлов");
        }
        String declared = safeMime(in.contentType());
        UUID id = Ids.newId();
        UUID tenant = TenantContext.require();
        String key = "quarantine/" + tenant + "/" + id;
        Instant expires = now.plus(props.uploadUrlTtl());
        AttachmentRow row = new AttachmentRow(id, tenant, ownerType, in.ownerId(), visibility, name, declared, null,
                in.size(), null, key, AttachmentRow.AWAITING, null, null, null, 0, expires, null, now, me, now, 0);
        store.insert(row);
        ObjectStorage.UploadUrl url = storage.presignUpload(key, declared, in.size(), props.uploadUrlTtl());
        return new AttachmentViews.Created(view(row, rights, me),
                new AttachmentViews.Upload(url.url(), url.method(), url.headers(), expires));
    }

    /** Файл загружен: проверить размер и отправить на проверку. Повторный вызов безопасен. */
    @Transactional
    AttachmentViews.Attachment complete(UUID id) {
        UUID me = me();
        AttachmentRow a = store.lock(id).orElseThrow(() -> ApiException.notFound("Файл"));
        OwnerAccess.Rights rights = readable(a);
        if (!AttachmentRow.AWAITING.equals(a.status())) {
            return view(a, rights, me);
        }
        if (!me.equals(a.createdBy())) {
            throw ApiException.forbidden();
        }
        Long size = storage.quarantineSize(a.storageKey())
                .orElseThrow(() -> ApiException.conflict("upload_missing", "Файл ещё не загружен"));
        if (size != a.sizeBytes()) {
            storage.deleteQuarantine(a.storageKey());
            throw ApiException.conflict("upload_size_mismatch", "Размер файла не совпадает с заявленным");
        }
        store.markScanning(a.id(), me, clock.instant());
        return view(store.find(id).orElseThrow(), rights, me);
    }

    @Transactional(readOnly = true)
    List<AttachmentViews.Attachment> list(String ownerType, UUID ownerId) {
        UUID me = me();
        String type = ownerType.strip().toLowerCase(Locale.ROOT);
        OwnerAccess.Rights rights = owners.rights(type, ownerId).orElseThrow(() -> ApiException.notFound("Владелец"));
        return store.byOwner(type, ownerId, rights.canSeeInternal()).stream()
                .map(a -> view(a, rights, me)).toList();
    }

    @Transactional(readOnly = true)
    AttachmentViews.Attachment get(UUID id) {
        AttachmentRow a = store.find(id).orElseThrow(() -> ApiException.notFound("Файл"));
        return view(a, readable(a), me());
    }

    /** Подписанная ссылка на скачивание чистого файла. */
    @Transactional(readOnly = true)
    String downloadUrl(UUID id, boolean preview) {
        AttachmentRow a = store.find(id).orElseThrow(() -> ApiException.notFound("Файл"));
        readable(a);
        requireClean(a);
        boolean inline = preview && a.contentType() != null && ContentSniffer.PREVIEWABLE.contains(a.contentType());
        if (preview && !inline) {
            throw ApiException.conflict("preview_unavailable", "Этот файл можно только скачать");
        }
        return storage.presignDownload(a.cleanKey(), a.fileName(), a.contentType(), inline, props.downloadUrlTtl());
    }

    @Transactional
    void delete(UUID id) {
        UUID me = me();
        AttachmentRow a = store.lock(id).orElseThrow(() -> ApiException.notFound("Файл"));
        OwnerAccess.Rights rights = readable(a);
        if (!canDelete(a, rights, me)) {
            throw ApiException.forbidden();
        }
        store.softDelete(a.id(), me, clock.instant());
        // Объект стираем после фиксации: при откате запись осталась бы без файла. Проверяемый файл сотрёт
        // сканер, увидев отметку удаления.
        if (!AttachmentRow.SCANNING.equals(a.status())) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (AttachmentRow.CLEAN.equals(a.status())) {
                            storage.deleteClean(a.cleanKey());
                        } else {
                            storage.deleteQuarantine(a.storageKey());
                        }
                    } catch (ObjectStorage.StorageUnavailable e) {
                        log.warn("Файл {} удалён, но объект в S3 остался: {}", a.id(), e.getMessage());
                    }
                }
            });
        }
    }

    /** Карантин арендатора для офицера безопасности (SEC-26). */
    @Transactional(readOnly = true)
    CursorPage<AttachmentViews.Attachment> quarantine(PageParams page) {
        UUID me = me();
        KeysetCursor after = page.keyset();
        Instant at = after == null ? null : Instant.parse(after.sortValue());
        List<AttachmentRow> rows = store.quarantine(at, after == null ? null : after.id(), page.limit() + 1);
        OwnerAccess.Rights none = new OwnerAccess.Rights(false, false, true, true);
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.updatedAt().toString(), r.id()))
                .map(a -> view(a, none, me));
    }

    /** Повторная проверка файла из карантина: например, после обновления баз антивируса. */
    @Transactional
    AttachmentViews.Attachment rescan(UUID id) {
        AttachmentRow a = store.lock(id).orElseThrow(() -> ApiException.notFound("Файл"));
        if (!a.quarantined() && !AttachmentRow.SCANNING.equals(a.status())) {
            throw ApiException.conflict("not_in_quarantine", "Файл не в карантине");
        }
        store.markScanning(a.id(), me(), clock.instant());
        return view(store.find(id).orElseThrow(), new OwnerAccess.Rights(false, false, true, true), me());
    }

    private OwnerAccess.Rights readable(AttachmentRow a) {
        OwnerAccess.Rights rights = owners.rights(a.ownerType(), a.ownerId())
                .orElseThrow(() -> ApiException.notFound("Файл"));
        if (a.internal() && !rights.canSeeInternal()) {
            throw ApiException.notFound("Файл");
        }
        if (AttachmentRow.AWAITING.equals(a.status()) && !me().equals(a.createdBy())) {
            throw ApiException.notFound("Файл");
        }
        return rights;
    }

    private static void requireClean(AttachmentRow a) {
        switch (a.status()) {
            case AttachmentRow.CLEAN -> {
            }
            case AttachmentRow.AWAITING, AttachmentRow.SCANNING ->
                    throw ApiException.conflict("attachment_scanning", "Файл ещё проверяется");
            default -> throw ApiException.conflict("attachment_quarantined", "Файл в карантине: скачать нельзя");
        }
    }

    private boolean canDelete(AttachmentRow a, OwnerAccess.Rights rights, UUID me) {
        return me.equals(a.createdBy()) || rights.canManage() || roles().contains("ADM") || roles().contains("SEC");
    }

    private AttachmentViews.Attachment view(AttachmentRow a, OwnerAccess.Rights rights, UUID me) {
        boolean previewable = AttachmentRow.CLEAN.equals(a.status()) && a.contentType() != null
                && ContentSniffer.PREVIEWABLE.contains(a.contentType());
        return new AttachmentViews.Attachment(a.id(), a.ownerType(), a.ownerId(), a.visibility(), a.fileName(),
                a.contentType() == null ? a.declaredType() : a.contentType(), a.sizeBytes(), a.status(), previewable,
                a.quarantined() || AttachmentRow.SCANNING.equals(a.status()) ? a.scanVerdict() : null, a.sha256(),
                a.scannedAt(), a.createdBy(), a.createdAt(), canDelete(a, rights, me), a.version());
    }

    private static UUID me() {
        return CurrentActor.userId().orElseThrow(ApiException::forbidden);
    }

    private static Set<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (GrantedAuthority g : auth.getAuthorities()) {
            if (g.getAuthority() != null && g.getAuthority().startsWith("ROLE_")) {
                out.add(g.getAuthority().substring(5));
            }
        }
        return out;
    }

    /** Имя без пути и управляющих символов: его покажут в интерфейсе и подставят в Content-Disposition. */
    static String cleanName(String raw) {
        String s = raw.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(c -> !Character.isISOControl(c) && c != 0x202e && c != 0x202d && c != 0x200f
                && c != 0x200e).forEach(sb::appendCodePoint);
        String name = sb.toString().strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isBlank()) {
            throw ApiException.badRequest("invalid_file_name", "Пустое имя файла");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** Заявленный тип нужен только для подписи загрузки: в хранилище он не влияет на отдачу файла. */
    static String safeMime(String mime) {
        if (mime == null || !mime.matches("^[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}$")) {
            return "application/octet-stream";
        }
        return mime.toLowerCase(Locale.ROOT);
    }
}
