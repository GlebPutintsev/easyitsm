-- Исходящие вебхуки (раздел 8.3 и 8.5 ТЗ, AUT-05): получатели, разрешённые адреса и очередь доставки
-- с подписью HMAC-SHA256, 8 повторами с растущей паузой и ручным повтором.

-- Разрешённые адреса вебхуков арендатора (задаёт ADM): имя узла, *.домен или сеть CIDR.
CREATE TABLE integration_outbound_policy (
    tenant_id     uuid PRIMARY KEY REFERENCES tenant (id),
    allowed_hosts text[] NOT NULL DEFAULT '{}',
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);

ALTER TABLE integration_outbound_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_outbound_policy
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

CREATE TABLE integration_webhook (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    name          text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    url           text NOT NULL CHECK (char_length(url) BETWEEN 8 AND 2000),
    -- Ключ подписи, зашифрованный AES-256-GCM; показывается один раз при создании и перевыпуске.
    secret        text NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    last_used_at  timestamptz,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE INDEX integration_webhook_tenant_idx ON integration_webhook (tenant_id, name);

ALTER TABLE integration_webhook ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_webhook
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

CREATE TABLE integration_webhook_delivery (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    webhook_id      uuid NOT NULL REFERENCES integration_webhook (id) ON DELETE CASCADE,
    event_type      text NOT NULL,
    -- Тело целиком (id, type, schema_version, occurred_at, tenant, data): повтор шлёт те же байты.
    payload         text NOT NULL,
    status          text NOT NULL CHECK (status IN ('pending', 'delivered', 'dead')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_status     integer,
    last_error      text,
    delivered_at    timestamptz,
    source          text,
    ticket_number   text,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
CREATE INDEX integration_webhook_delivery_due_idx ON integration_webhook_delivery (next_attempt_at)
    WHERE status = 'pending';
CREATE INDEX integration_webhook_delivery_list_idx ON integration_webhook_delivery (tenant_id, created_at DESC, id DESC);
CREATE INDEX integration_webhook_delivery_hook_idx ON integration_webhook_delivery (tenant_id, webhook_id, created_at DESC);

ALTER TABLE integration_webhook_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_webhook_delivery
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Доставки, у которых наступил срок, во всех арендаторах: фоновому обработчику — только идентификаторы.
CREATE FUNCTION integration_webhook_due(p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT d.tenant_id, d.id FROM integration_webhook_delivery d
     WHERE d.status = 'pending' AND d.next_attempt_at <= now()
     ORDER BY d.next_attempt_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION integration_webhook_due(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_webhook_due(int) TO ${app_role};

-- Доставленные и неудачные старше срока хранения удаляются вместе с журналом мониторинга.
CREATE OR REPLACE FUNCTION integration_purge_events(keep_days integer) RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
    WITH events AS (
        DELETE FROM integration_event_log
         WHERE received_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1),
    deliveries AS (
        DELETE FROM integration_webhook_delivery
         WHERE status <> 'pending' AND created_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1)
    SELECT ((SELECT count(*) FROM events) + (SELECT count(*) FROM deliveries))::int
$$;
