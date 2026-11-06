package ru.easyitsm.attachments.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL таблицы attachment. Арендатор — RLS. */
@Repository
class AttachmentStore {

    private static final String COLUMNS = """
            id, tenant_id, owner_type, owner_id, visibility, file_name, declared_type, content_type, size_bytes,
            sha256, storage_key, status, scan_engine, scan_verdict, scanned_at, scan_attempts, upload_expires_at,
            deleted_at, created_at, created_by, updated_at, version""";

    private final JdbcClient jdbc;

    AttachmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(AttachmentRow a) {
        jdbc.sql("""
                        insert into attachment (id, tenant_id, owner_type, owner_id, visibility, file_name, declared_type,
                            size_bytes, storage_key, status, upload_expires_at, created_at, created_by, updated_at,
                            updated_by, version)
                        values (:id, :tenant, :ownerType, :ownerId, :visibility, :fileName, :declaredType, :size, :key,
                            :status, :expires, :now, :by, :now, :by, 0)""")
                .param("id", a.id()).param("tenant", a.tenantId()).param("ownerType", a.ownerType())
                .param("ownerId", a.ownerId()).param("visibility", a.visibility()).param("fileName", a.fileName())
                .param("declaredType", a.declaredType()).param("size", a.sizeBytes()).param("key", a.storageKey())
                .param("status", a.status()).param("expires", ts(a.uploadExpiresAt())).param("now", ts(a.createdAt()))
                .param("by", a.createdBy())
                .update();
    }

    Optional<AttachmentRow> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from attachment where id = :id and deleted_at is null")
                .param("id", id).query(AttachmentStore::map).optional();
    }

    Optional<AttachmentRow> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from attachment where id = :id and deleted_at is null for update")
                .param("id", id).query(AttachmentStore::map).optional();
    }

    List<AttachmentRow> byOwner(String ownerType, UUID ownerId, boolean withInternal) {
        return jdbc.sql("select " + COLUMNS + " from attachment where owner_type = :type and owner_id = :owner"
                        + " and deleted_at is null and status <> 'awaiting_upload'"
                        + (withInternal ? "" : " and visibility = 'public'") + " order by created_at, id")
                .param("type", ownerType).param("owner", ownerId).query(AttachmentStore::map).list();
    }

    /** Файлы в карантине (заражённые и заблокированные), новые сверху, курсор по (updated_at, id). */
    List<AttachmentRow> quarantine(Instant beforeAt, UUID beforeId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from attachment where status in ('infected', 'blocked')"
                        + " and deleted_at is null"
                        + " and (cast(:at as timestamptz) is null or (updated_at, id) < (cast(:at as timestamptz), :bid))"
                        + " order by updated_at desc, id desc limit :limit")
                .param("at", ts(beforeAt)).param("bid", beforeId == null ? new UUID(0, 0) : beforeId)
                .param("limit", limit).query(AttachmentStore::map).list();
    }

    long countByOwner(String ownerType, UUID ownerId) {
        return jdbc.sql("select count(*) from attachment where owner_type = :type and owner_id = :owner"
                        + " and deleted_at is null")
                .param("type", ownerType).param("owner", ownerId).query(Long.class).single();
    }

    long uploadsSince(UUID userId, Instant since) {
        return jdbc.sql("select count(*) from attachment where created_by = :u and created_at >= :since")
                .param("u", userId).param("since", ts(since)).query(Long.class).single();
    }

    void markScanning(UUID id, UUID by, Instant now) {
        jdbc.sql("""
                        update attachment set status = 'scanning', next_scan_at = :now, scan_attempts = 0,
                               leased_until = null, upload_expires_at = null, updated_at = :now, updated_by = :by,
                               version = version + 1
                         where id = :id""")
                .param("id", id).param("by", by).param("now", ts(now)).update();
    }

    /** Итог проверки. */
    void finishScan(UUID id, String status, String contentType, String sha256, String engine, String verdict,
                    Instant now) {
        jdbc.sql("""
                        update attachment set status = :status, content_type = :ct, sha256 = :sha, scan_engine = :engine,
                               scan_verdict = left(:verdict, 500), scanned_at = :now, next_scan_at = null,
                               leased_until = null, updated_at = :now, version = version + 1
                         where id = :id""")
                .param("id", id).param("status", status).param("ct", contentType).param("sha", sha256)
                .param("engine", engine).param("verdict", verdict).param("now", ts(now)).update();
    }

    /** Проверка не удалась (антивирус недоступен): повтор с растущей паузой до 10 минут. */
    void retryScan(UUID id, int attempts, String error, Instant now) {
        long delay = Math.min(600, (long) Math.pow(2, Math.min(attempts, 10)));
        jdbc.sql("""
                        update attachment set next_scan_at = :next, leased_until = null, scan_verdict = left(:error, 500),
                               updated_at = :now
                         where id = :id""")
                .param("id", id).param("next", ts(now.plusSeconds(delay))).param("error", error)
                .param("now", ts(now)).update();
    }

    void softDelete(UUID id, UUID by, Instant now) {
        jdbc.sql("""
                        update attachment set deleted_at = :now, deleted_by = :by, updated_at = :now, updated_by = :by,
                               version = version + 1
                         where id = :id""")
                .param("id", id).param("by", by).param("now", ts(now)).update();
    }

    /** Незавершённая загрузка: записи как не было. */
    void purge(UUID id) {
        jdbc.sql("delete from attachment where id = :id and status = 'awaiting_upload'").param("id", id).update();
    }

    record Due(UUID tenantId, UUID id) {
    }

    List<Due> claimScans(int limit, int leaseSeconds) {
        return jdbc.sql("select tenant_id, id from attachments_claim_scans(:limit, :lease)")
                .param("limit", limit).param("lease", leaseSeconds)
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
    }

    List<Due> expiredUploads(int limit) {
        return jdbc.sql("select tenant_id, id from attachments_expired_uploads(:limit)").param("limit", limit)
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
    }

    /** Запись для проверки, в том числе удалённая во время загрузки. */
    Optional<AttachmentRow> findAny(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from attachment where id = :id").param("id", id)
                .query(AttachmentStore::map).optional();
    }

    private static AttachmentRow map(ResultSet rs, int n) throws SQLException {
        return new AttachmentRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("owner_type"), rs.getObject("owner_id", UUID.class), rs.getString("visibility"),
                rs.getString("file_name"), rs.getString("declared_type"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("storage_key"), rs.getString("status"),
                rs.getString("scan_engine"), rs.getString("scan_verdict"), instant(rs, "scanned_at"),
                rs.getInt("scan_attempts"), instant(rs, "upload_expires_at"), instant(rs, "deleted_at"),
                instant(rs, "created_at"), rs.getObject("created_by", UUID.class), instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime t = rs.getObject(c, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
