-- Отчёты и дашборды (RPT-01, RPT-04). Модуль не читает чужие таблицы: витрина ticket_fact строится
-- из событий модулей tickets, sla и surveys и хранит только то, что нужно для показателей.

-- Одна строка на заявку: последний снимок из событий заявки, плюс факты SLA и оценки.
CREATE TABLE reporting_ticket_fact (
    ticket_id           uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant (id),
    number              text NOT NULL,
    type                text NOT NULL,
    title               text NOT NULL,
    status_code         text NOT NULL,
    status_category     text NOT NULL,
    priority            text NOT NULL,
    channel             text,
    requester_id        uuid,
    group_id            uuid,
    assignee_id         uuid,
    -- Услуга для отчётов: услуга заявки, а если её нет — предложение каталога (как в рабочем месте агента).
    service_id          uuid,
    category_id         uuid,
    confidential        boolean NOT NULL DEFAULT false,
    due_at              timestamptz,
    created_at          timestamptz NOT NULL,
    first_response_at   timestamptz,
    resolved_at         timestamptz,
    closed_at           timestamptz,
    reopened_count      int NOT NULL DEFAULT 0,
    -- SLA: has_sla — у заявки был хотя бы один счётчик SLA (не OLA/UC), *_breached_at — первое нарушение.
    has_sla             boolean NOT NULL DEFAULT false,
    response_breached_at   timestamptz,
    resolution_breached_at timestamptz,
    csat_score          smallint CHECK (csat_score BETWEEN 1 AND 5),
    csat_at             timestamptz,
    -- Версия снимка заявки: устаревшее событие (пришло позже нового) не перезаписывает строку.
    ticket_version      bigint NOT NULL,
    updated_at          timestamptz NOT NULL
);
CREATE INDEX reporting_fact_created_idx ON reporting_ticket_fact (tenant_id, created_at);
CREATE INDEX reporting_fact_resolved_idx ON reporting_ticket_fact (tenant_id, resolved_at) WHERE resolved_at IS NOT NULL;
CREATE INDEX reporting_fact_open_idx ON reporting_ticket_fact (tenant_id, status_category)
    WHERE status_category IN ('new', 'approval', 'assigned', 'in_progress', 'pending');
CREATE INDEX reporting_fact_requester_idx ON reporting_ticket_fact (tenant_id, requester_id, created_at);
CREATE INDEX reporting_fact_group_idx ON reporting_ticket_fact (tenant_id, group_id);

ALTER TABLE reporting_ticket_fact ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reporting_ticket_fact
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Нарушения SLA по времени нарушения: для графика нарушений по дням. Один раз на счётчик.
CREATE TABLE reporting_sla_breach (
    instance_id  uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    ticket_id    uuid NOT NULL,
    kind         text NOT NULL,
    metric       text NOT NULL,
    priority     text,
    breached_at  timestamptz NOT NULL
);
CREATE INDEX reporting_breach_time_idx ON reporting_sla_breach (tenant_id, breached_at);
CREATE INDEX reporting_breach_ticket_idx ON reporting_sla_breach (tenant_id, ticket_id);

ALTER TABLE reporting_sla_breach ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reporting_sla_breach
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Журнал выгрузок (RPT-04, SEC-29): кто, что, в каком формате и сколько строк выгрузил.
-- Модуль аудита получает то же событием ReportExported. Журнал только дописывается.
CREATE TABLE reporting_export (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    report      text NOT NULL,
    format      text NOT NULL CHECK (format IN ('csv', 'xlsx', 'pdf')),
    filters     jsonb NOT NULL DEFAULT '{}'::jsonb,
    row_count   int NOT NULL,
    reason      text,
    created_at  timestamptz NOT NULL,
    created_by  uuid
);
CREATE INDEX reporting_export_time_idx ON reporting_export (tenant_id, created_at DESC, id DESC);

ALTER TABLE reporting_export ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reporting_export
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
REVOKE UPDATE, DELETE ON reporting_export FROM ${app_role};
