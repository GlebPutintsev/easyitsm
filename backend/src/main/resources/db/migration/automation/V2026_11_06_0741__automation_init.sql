-- Автоматизация (AUT-01, AUT-04): правила «событие — условие — действие» и журнал запусков.

CREATE TABLE automation_rule (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    description text NOT NULL DEFAULT '' CHECK (char_length(description) <= 2000),
    -- Событие: ticket_created, status_changed, priority_changed, assignment_changed, ticket_updated,
    -- comment_added, escalated, sla_warning, sla_breached. Список проверяет приложение, чтобы добавлять события
    -- без миграции.
    trigger     text NOT NULL,
    -- {"match": "all" | "any", "items": [{"field", "op", "value"}]}
    conditions  jsonb NOT NULL DEFAULT '{"match": "all", "items": []}',
    -- [{"type": "assign" | "set_priority" | "set_status" | "add_comment" | "add_watcher" | "escalate", ...}]
    actions     jsonb NOT NULL DEFAULT '[]',
    enabled     boolean NOT NULL DEFAULT false,
    position    integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE INDEX automation_rule_trigger_idx ON automation_rule (tenant_id, trigger, enabled, position, id);

ALTER TABLE automation_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_rule
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Журнал: каждый запуск правила, условия которого совпали, в том числе остановленный защитой от петель.
CREATE TABLE automation_run (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    -- Правило может быть удалено, запись журнала остаётся с его названием и версией.
    rule_id       uuid REFERENCES automation_rule (id) ON DELETE SET NULL,
    rule_name     text NOT NULL,
    rule_version  bigint NOT NULL,
    trigger       text NOT NULL,
    -- Одно событие запускает правило один раз, даже если outbox доставит событие повторно после перезапуска.
    run_key       text NOT NULL,
    ticket_id     uuid,
    ticket_number text,
    depth         integer NOT NULL CHECK (depth >= 1),
    chain         uuid[] NOT NULL DEFAULT '{}',
    status        text NOT NULL CHECK (status IN ('success', 'failed', 'skipped')),
    skip_reason   text CHECK (skip_reason IN ('depth_limit', 'loop', 'rate_limit')),
    results       jsonb NOT NULL DEFAULT '[]',
    error_code    text,
    error         text,
    started_at    timestamptz NOT NULL,
    duration_ms   integer NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL,
    CHECK ((status = 'skipped') = (skip_reason IS NOT NULL))
);
CREATE UNIQUE INDEX automation_run_key_uq ON automation_run (tenant_id, run_key);
CREATE INDEX automation_run_list_idx ON automation_run (tenant_id, started_at DESC, id DESC);
CREATE INDEX automation_run_rule_idx ON automation_run (tenant_id, rule_id, started_at DESC);
CREATE INDEX automation_run_ticket_idx ON automation_run (tenant_id, ticket_id, started_at DESC);

ALTER TABLE automation_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_run
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Очистка журнала старше заданного числа дней по всем арендаторам. SECURITY DEFINER, как в platform.
CREATE FUNCTION automation_purge_runs(keep_days integer) RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
    WITH deleted AS (
        DELETE FROM automation_run WHERE started_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1)
    SELECT count(*)::int FROM deleted
$$;
REVOKE ALL ON FUNCTION automation_purge_runs(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION automation_purge_runs(integer) TO ${app_role};
