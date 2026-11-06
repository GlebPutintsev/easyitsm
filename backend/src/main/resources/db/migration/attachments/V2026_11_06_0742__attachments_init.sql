-- Вложения (TCK-04, SEC-25…27): файлы заявок и статей в S3-совместимом хранилище.
-- Файл сначала попадает в карантин (префикс quarantine/), проверяется по содержимому и антивирусом и только
-- после этого копируется в files/ и становится доступен по подписанной ссылке.
CREATE TABLE attachment (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    owner_type    text NOT NULL CHECK (owner_type IN ('ticket', 'article')),
    owner_id      uuid NOT NULL,
    visibility    text NOT NULL DEFAULT 'public' CHECK (visibility IN ('public', 'internal')),
    file_name     text NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    declared_type text NOT NULL DEFAULT 'application/octet-stream',
    -- Тип по содержимому (сигнатура файла), а не по имени и не по словам клиента.
    content_type  text,
    size_bytes    bigint NOT NULL CHECK (size_bytes > 0),
    sha256        text,
    storage_key   text NOT NULL,
    -- awaiting_upload — ждём загрузку по ссылке; scanning — в карантине, идёт проверка;
    -- clean — можно скачать; infected — найден вредоносный код; blocked — запрещено политикой.
    status        text NOT NULL CHECK (status IN ('awaiting_upload', 'scanning', 'clean', 'infected', 'blocked')),
    scan_engine   text,
    scan_verdict  text,
    scanned_at    timestamptz,
    scan_attempts int NOT NULL DEFAULT 0,
    next_scan_at  timestamptz,
    leased_until  timestamptz,
    upload_expires_at timestamptz,
    deleted_at    timestamptz,
    deleted_by    uuid,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE INDEX attachment_owner_idx ON attachment (tenant_id, owner_type, owner_id, created_at) WHERE deleted_at IS NULL;
CREATE INDEX attachment_scan_idx ON attachment (next_scan_at) WHERE status = 'scanning';
CREATE INDEX attachment_upload_idx ON attachment (upload_expires_at) WHERE status = 'awaiting_upload';
CREATE INDEX attachment_quarantine_idx ON attachment (tenant_id, updated_at DESC, id DESC)
    WHERE status IN ('infected', 'blocked');
CREATE INDEX attachment_rate_idx ON attachment (tenant_id, created_by, created_at DESC);

ALTER TABLE attachment ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attachment
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Проверка забирает файлы по всем арендаторам с арендой: два пода worker не проверяют один файл.
CREATE FUNCTION attachments_claim_scans(p_limit int, p_lease_seconds int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public
AS $$
    UPDATE attachment a
       SET leased_until = now() + make_interval(secs => p_lease_seconds), scan_attempts = a.scan_attempts + 1
      FROM (SELECT s.id
              FROM attachment s
             WHERE s.status = 'scanning' AND s.deleted_at IS NULL AND s.next_scan_at <= now()
               AND (s.leased_until IS NULL OR s.leased_until < now())
             ORDER BY s.next_scan_at
             LIMIT p_limit
               FOR UPDATE SKIP LOCKED) c
     WHERE a.id = c.id
    RETURNING a.tenant_id, a.id
$$;
REVOKE ALL ON FUNCTION attachments_claim_scans(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION attachments_claim_scans(int, int) TO ${app_role};

-- Незавершённые загрузки: ссылка истекла час назад, файл так и не подтвердили. Их удаляет очистка.
CREATE FUNCTION attachments_expired_uploads(p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT a.tenant_id, a.id
      FROM attachment a
     WHERE a.status = 'awaiting_upload' AND a.upload_expires_at < now() - interval '1 hour'
     ORDER BY a.upload_expires_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION attachments_expired_uploads(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION attachments_expired_uploads(int) TO ${app_role};
