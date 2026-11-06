-- Проблемы и известные ошибки (PRB-01…05, 7.4 ТЗ). Сама проблема — заявка типа problem модуля tickets,
-- здесь только то, что есть у проблемы сверх заявки: анализ причин, известная ошибка, изменения-исправления.

CREATE TABLE problem_detail (
    ticket_id        uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    -- Откуда проблема: manual, incident (PRB-01), recurring (PRB-05), major_incident (MIM-05).
    source           text NOT NULL DEFAULT 'manual'
                         CHECK (source IN ('manual', 'incident', 'recurring', 'major_incident')),
    root_cause       text NOT NULL DEFAULT '' CHECK (char_length(root_cause) <= 20000),
    workaround       text NOT NULL DEFAULT '' CHECK (char_length(workaround) <= 20000),
    -- Анализ причин (PRB-02): хронология, «5 почему», Исикава, заключение.
    analysis         jsonb NOT NULL DEFAULT '{}',
    -- Решить связанные инциденты, когда проблема решена (PRB-04).
    close_incidents  boolean NOT NULL DEFAULT true,
    -- Принятый риск без исправления: кто принял и когда пересмотреть.
    risk_accepted_by uuid,
    risk_accepted_at timestamptz,
    risk_review_at   timestamptz,
    risk_reason      text CHECK (risk_reason IS NULL OR char_length(risk_reason) <= 2000),
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL
);

-- Инциденты, которые модуль связал с проблемой: отметка «уже есть проблема» в отчёте повторов (PRB-05).
-- Сами связи живут в ticket_link модуля tickets.
CREATE TABLE problem_incident (
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    problem_id  uuid NOT NULL,
    incident_id uuid NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, problem_id, incident_id)
);
CREATE INDEX problem_incident_incident ON problem_incident (tenant_id, incident_id);

-- Изменения, созданные для исправления проблемы (PRB-04).
CREATE TABLE problem_change (
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    problem_id uuid NOT NULL,
    change_id  uuid NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, problem_id, change_id)
);
CREATE INDEX problem_change_change ON problem_change (tenant_id, change_id);

-- Известная ошибка (PRB-03): симптомы и обходное решение, видны агентам при инцидентах после публикации.
CREATE TABLE known_error (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    problem_id   uuid NOT NULL,
    title        text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    symptoms     text NOT NULL DEFAULT '' CHECK (char_length(symptoms) <= 20000),
    workaround   text NOT NULL DEFAULT '' CHECK (char_length(workaround) <= 20000),
    service_id   uuid,
    ci_id        uuid,
    category_id  uuid,
    published    boolean NOT NULL DEFAULT false,
    published_at timestamptz,
    published_by uuid,
    -- Ошибка исправлена (проблема решена): остаётся в базе, но в подсказках ниже.
    fixed        boolean NOT NULL DEFAULT false,
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('russian', title), 'A') ||
        setweight(to_tsvector('russian', symptoms), 'B') ||
        setweight(to_tsvector('russian', workaround), 'C')) STORED,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL,
    UNIQUE (tenant_id, problem_id)
);
CREATE INDEX known_error_search ON known_error USING gin (search_vector);
CREATE INDEX known_error_match ON known_error (tenant_id, published, service_id, ci_id, category_id);

ALTER TABLE problem_detail ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON problem_detail
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE problem_incident ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON problem_incident
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE problem_change ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON problem_change
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE known_error ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON known_error
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

