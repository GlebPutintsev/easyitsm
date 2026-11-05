-- Ядро платформы: арендаторы, функция текущего арендатора для RLS, outbox событий, идемпотентность.
-- Миграции выполняются ролью владельца. Роль приложения ${app_role} получает только DML.
--
-- RLS действует на роль приложения. Владелец таблиц (FORCE не включаем) обходит RLS: это нужно
-- миграциям данных и служебным функциям SECURITY DEFINER. Поэтому приложение никогда не
-- подключается ролью владельца, а тест TenantIsolationTest проверяет это.

-- Текущий арендатор соединения. Пустое значение = NULL, и политики RLS не пропускают ни одной строки.
CREATE FUNCTION app_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

GRANT EXECUTE ON FUNCTION app_current_tenant() TO ${app_role};

-- Роль приложения получает доступ к новым таблицам владельца автоматически.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

-- Арендаторы. Видны приложению только свои: строка арендатора тоже под RLS.
CREATE TABLE tenant (
    id         uuid PRIMARY KEY,
    code       text NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name       text NOT NULL,
    state      text NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'suspended', 'terminated')),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant USING (id = app_current_tenant());
REVOKE INSERT, UPDATE, DELETE ON tenant FROM ${app_role};

-- Outbox доменных событий (Spring Modulith event publication registry, схема v2), ADR-03.
-- Платформенная таблица без tenant_id: события несут арендатора в своём теле.
CREATE TABLE event_publication (
    id                     uuid NOT NULL PRIMARY KEY,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamptz
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

-- Идемпотентность POST-запросов, API-04.
CREATE TABLE idempotency_record (
    tenant_id     uuid NOT NULL,
    actor_id      uuid NOT NULL,
    idem_key      text NOT NULL,
    request_hash  text NOT NULL,
    status_code   int,
    content_type  text,
    response_body bytea,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, actor_id, idem_key)
);
CREATE INDEX idempotency_record_created_idx ON idempotency_record (created_at);
ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_record
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Очистка старше 24 часов по всем арендаторам. SECURITY DEFINER: выполняется с правами владельца,
-- поэтому приложению не нужен обход RLS.
CREATE FUNCTION platform_purge_idempotency() RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
    WITH deleted AS (DELETE FROM idempotency_record WHERE created_at < now() - interval '24 hours' RETURNING 1)
    SELECT count(*)::int FROM deleted
$$;
REVOKE ALL ON FUNCTION platform_purge_idempotency() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_purge_idempotency() TO ${app_role};
