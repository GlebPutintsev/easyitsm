-- Интеграции: приём событий мониторинга (раздел 8.4 ТЗ, INC-01) — Zabbix, Prometheus Alertmanager, Grafana
-- и общий JSON. Источник шлёт события с токеном, по отпечатку алерта одна открытая проблема — один инцидент.

CREATE TABLE integration_monitoring_source (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    kind            text NOT NULL CHECK (kind IN ('zabbix', 'alertmanager', 'generic')),
    -- SHA-256 токена. Сам токен показывается один раз при создании и перевыпуске.
    token_hash      bytea NOT NULL,
    token_hint      text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    -- От чьего имени создаются инциденты: служебный пользователь источника.
    requester_id    uuid NOT NULL,
    group_id        uuid,
    -- Ниже этой важности события только пишутся в журнал.
    min_severity    text NOT NULL DEFAULT 'warning' CHECK (min_severity IN ('critical', 'high', 'warning', 'info')),
    -- {"critical": {"impact": "high", "urgency": "high"}, ...} — влияние и срочность по важности алерта.
    severity_map    jsonb NOT NULL,
    auto_resolve    boolean NOT NULL DEFAULT true,
    resolution_code text NOT NULL DEFAULT 'fixed',
    last_event_at   timestamptz,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
CREATE UNIQUE INDEX integration_monitoring_source_token_uq ON integration_monitoring_source (token_hash);
CREATE INDEX integration_monitoring_source_tenant_idx ON integration_monitoring_source (tenant_id, name);

ALTER TABLE integration_monitoring_source ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_monitoring_source
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Алерт: отпечаток (fingerprint) источника и инцидент, который его ведёт. Пока алерт активен (firing),
-- повторы того же отпечатка не создают новых заявок.
CREATE TABLE integration_alert (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    source_id     uuid NOT NULL REFERENCES integration_monitoring_source (id) ON DELETE CASCADE,
    fingerprint   text NOT NULL CHECK (char_length(fingerprint) BETWEEN 1 AND 500),
    ticket_id     uuid NOT NULL,
    ticket_number text NOT NULL,
    status        text NOT NULL CHECK (status IN ('firing', 'resolved')),
    severity      text NOT NULL CHECK (severity IN ('critical', 'high', 'warning', 'info')),
    title         text NOT NULL,
    occurrences   integer NOT NULL DEFAULT 1,
    first_at      timestamptz NOT NULL,
    last_at       timestamptz NOT NULL,
    resolved_at   timestamptz,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE UNIQUE INDEX integration_alert_firing_uq ON integration_alert (tenant_id, source_id, fingerprint)
    WHERE status = 'firing';
CREATE INDEX integration_alert_ticket_idx ON integration_alert (tenant_id, ticket_id);
CREATE INDEX integration_alert_list_idx ON integration_alert (tenant_id, source_id, last_at DESC);

ALTER TABLE integration_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_alert
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Журнал: итог каждого принятого события, в том числе пропущенного и ошибочного.
CREATE TABLE integration_event_log (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    source_id     uuid NOT NULL REFERENCES integration_monitoring_source (id) ON DELETE CASCADE,
    received_at   timestamptz NOT NULL,
    fingerprint   text,
    alert_status  text CHECK (alert_status IN ('firing', 'resolved')),
    severity      text,
    title         text,
    outcome       text NOT NULL CHECK (outcome IN ('created', 'updated', 'escalated', 'reopened', 'resolved',
                                                   'ignored', 'error')),
    ticket_id     uuid,
    ticket_number text,
    detail        text,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE INDEX integration_event_log_list_idx ON integration_event_log (tenant_id, received_at DESC, id DESC);
CREATE INDEX integration_event_log_source_idx ON integration_event_log (tenant_id, source_id, received_at DESC);

ALTER TABLE integration_event_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_event_log
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Источник по токену до установки арендатора: запрос мониторинга приходит без входа. SECURITY DEFINER,
-- наружу — только идентификаторы; дальше источник читается в своём арендаторе под RLS.
CREATE FUNCTION integration_source_by_token(p_hash bytea)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT s.tenant_id, s.id FROM integration_monitoring_source s WHERE s.token_hash = p_hash
$$;
REVOKE ALL ON FUNCTION integration_source_by_token(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_source_by_token(bytea) TO ${app_role};

CREATE FUNCTION integration_purge_events(keep_days integer) RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
    WITH deleted AS (
        DELETE FROM integration_event_log
         WHERE received_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1)
    SELECT count(*)::int FROM deleted
$$;
REVOKE ALL ON FUNCTION integration_purge_events(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_purge_events(integer) TO ${app_role};
