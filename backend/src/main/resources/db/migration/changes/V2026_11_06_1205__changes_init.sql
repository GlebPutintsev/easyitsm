-- Изменения и CAB (CHG-01…12, 5.6, 7.4 ТЗ). Изменение — заявка типа change в модуле tickets, здесь его данные:
-- тип, планы, окно, риск, согласования, разбор. Статус ведёт этот модуль и переносит в заявку переходом
-- от имени системы.

CREATE TABLE change_detail (
    ticket_id            uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant (id),
    -- Копия полей заявки для списков и календаря, обновляется по событиям модуля tickets.
    number               text NOT NULL,
    title                text NOT NULL,
    author_id            uuid NOT NULL,
    group_id             uuid,
    assignee_id          uuid,
    confidential         boolean NOT NULL DEFAULT false,
    change_type          text NOT NULL CHECK (change_type IN ('standard', 'normal', 'emergency')),
    state                text NOT NULL CHECK (state IN ('draft', 'assessment', 'approval', 'scheduled', 'implementation',
                                                        'verification', 'review', 'closed', 'rejected', 'cancelled')),
    template_id          uuid,
    source_ticket_id     uuid,
    -- CHG-02: цель, обоснование, влияние, негативные события, суть изменений.
    goal                 text NOT NULL DEFAULT '' CHECK (char_length(goal) <= 20000),
    justification        text NOT NULL DEFAULT '' CHECK (char_length(justification) <= 20000),
    impact               text NOT NULL DEFAULT '' CHECK (char_length(impact) <= 20000),
    risks                text NOT NULL DEFAULT '' CHECK (char_length(risks) <= 20000),
    -- [{"service", "description", "duration"}] — таблица влияния на ИТ-сервисы для плана работ.
    impact_rows          jsonb NOT NULL DEFAULT '[]',
    system_criticality   text CHECK (system_criticality IN ('critical', 'high', 'medium', 'low')),
    window_start         timestamptz,
    window_end           timestamptz,
    time_zone            text NOT NULL DEFAULT 'Europe/Moscow',
    prep_start           timestamptz,
    -- Ожидаемый простой: null — не указан, 0 — без простоя.
    downtime_minutes     integer CHECK (downtime_minutes >= 0),
    ci_ids               uuid[] NOT NULL DEFAULT '{}',
    service_ids          uuid[] NOT NULL DEFAULT '{}',
    -- CHG-04: анкета риска, рассчитанный и итоговый уровень.
    risk_answers         jsonb NOT NULL DEFAULT '{}',
    risk_score           integer,
    risk_level           text CHECK (risk_level IN ('low', 'medium', 'high')),
    risk_manual          boolean NOT NULL DEFAULT false,
    risk_reason          text,
    risk_assessed_at     timestamptz,
    risk_assessed_by     uuid,
    -- Конфликты, с которыми изменение отправлено на согласование, и причина.
    conflicts_ack        text,
    submitted_at         timestamptz,
    approved_at          timestamptz,
    -- CHG-12: срок последующего согласования экстренного изменения.
    post_approval_due_at timestamptz,
    actual_start         timestamptz,
    actual_end           timestamptz,
    rollback_started_at  timestamptz,
    -- CHG-11: результат и разбор.
    outcome              text CHECK (outcome IN ('successful', 'partial', 'rolled_back')),
    conclusion           text,
    review               jsonb NOT NULL DEFAULT '{}',
    closed_at            timestamptz,
    cancel_reason        text,
    -- CHG-10: источник из CI/CD (система, репозиторий, ветка или тег, окружение, ссылка на конвейер).
    pipeline             jsonb,
    created_at           timestamptz NOT NULL,
    created_by           uuid,
    updated_at           timestamptz NOT NULL,
    updated_by           uuid,
    version              bigint NOT NULL,
    CHECK (window_end IS NULL OR window_start IS NULL OR window_end > window_start)
);
CREATE INDEX change_detail_window_idx ON change_detail (tenant_id, window_start, window_end);
CREATE INDEX change_detail_state_idx ON change_detail (tenant_id, state, updated_at DESC);
CREATE INDEX change_detail_ci_idx ON change_detail USING gin (ci_ids);
CREATE INDEX change_detail_service_idx ON change_detail USING gin (service_ids);

ALTER TABLE change_detail ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_detail
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- CHG-03: план работ по шагам. Фазы как в согласованной форме плана: подготовка, внедрение, проверка, возврат.
CREATE TABLE change_step (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    change_id        uuid NOT NULL REFERENCES change_detail (ticket_id) ON DELETE CASCADE,
    phase            text NOT NULL CHECK (phase IN ('prepare', 'implement', 'verify', 'rollback')),
    seq              integer NOT NULL CHECK (seq >= 1),
    title            text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 500),
    description      text NOT NULL DEFAULT '' CHECK (char_length(description) <= 20000),
    owner_id         uuid,
    owner_name       text,
    team             text,
    contacts         text,
    -- Явное время начала; без него шаг начинается после предыдущего шага фазы.
    planned_start    timestamptz,
    duration_minutes integer NOT NULL DEFAULT 0 CHECK (duration_minutes >= 0),
    -- Длительность «до N мин».
    upto             boolean NOT NULL DEFAULT false,
    interruption     text,
    check_criteria   text,
    -- Номер шага возврата, который отменяет этот шаг.
    rollback_ref     integer,
    status           text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'done', 'failed', 'skipped')),
    result_note      text,
    executed_at      timestamptz,
    executed_by      uuid,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL,
    UNIQUE (change_id, phase, seq)
);

ALTER TABLE change_step ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_step
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Маршрут согласования: слот на менеджера изменений, группу согласования, конкретного согласующего или CAB.
-- Перепланирование отменяет открытые слоты (superseded) и строит маршрут заново.
CREATE TABLE change_approval (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    change_id   uuid NOT NULL REFERENCES change_detail (ticket_id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('manager', 'group', 'user', 'cab')),
    group_id    uuid,
    approver_id uuid,
    round       integer NOT NULL DEFAULT 1,
    post_factum boolean NOT NULL DEFAULT false,
    decision    text NOT NULL DEFAULT 'pending' CHECK (decision IN ('pending', 'approved', 'rejected', 'superseded')),
    comment     text,
    decided_by  uuid,
    decided_at  timestamptz,
    due_at      timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE INDEX change_approval_change_idx ON change_approval (change_id, round, created_at);
CREATE INDEX change_approval_pending_idx ON change_approval (tenant_id, decision, kind);

ALTER TABLE change_approval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_approval
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- CHG-08: шаблоны. Стандартное изменение из шаблона с предварительным утверждением минует оценку и согласование.
CREATE TABLE change_template (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    name         text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    description  text NOT NULL DEFAULT '' CHECK (char_length(description) <= 4000),
    change_type  text NOT NULL CHECK (change_type IN ('standard', 'normal', 'emergency')),
    preapproved  boolean NOT NULL DEFAULT false,
    risk_level   text CHECK (risk_level IN ('low', 'medium', 'high')),
    -- Ключ для CI/CD (CHG-10): конвейер создаёт изменение по шаблону с этим ключом.
    pipeline_key text CHECK (pipeline_key ~ '^[a-z0-9][a-z0-9._-]{1,63}$'),
    -- {"goal", "justification", "impact", "risks", "downtimeMinutes", "durationMinutes", "systemCriticality"}
    content      jsonb NOT NULL DEFAULT '{}',
    -- Шаги в формате плана, без результатов.
    steps        jsonb NOT NULL DEFAULT '[]',
    ci_ids       uuid[] NOT NULL DEFAULT '{}',
    service_ids  uuid[] NOT NULL DEFAULT '{}',
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL,
    CHECK (NOT preapproved OR change_type = 'standard')
);
CREATE UNIQUE INDEX change_template_pipeline_uq ON change_template (tenant_id, pipeline_key) WHERE pipeline_key IS NOT NULL;

ALTER TABLE change_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_template
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- CHG-05: периоды заморозки изменений — для всех или для отдельных услуг и КЕ.
CREATE TABLE change_freeze (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    title       text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    reason      text NOT NULL DEFAULT '' CHECK (char_length(reason) <= 4000),
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    scope       text NOT NULL CHECK (scope IN ('all', 'targeted')),
    service_ids uuid[] NOT NULL DEFAULT '{}',
    ci_ids      uuid[] NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL,
    CHECK (ends_at > starts_at)
);
CREATE INDEX change_freeze_window_idx ON change_freeze (tenant_id, starts_at, ends_at);

ALTER TABLE change_freeze ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_freeze
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Настройки модуля одним документом: маршруты согласования по уровню риска, срок последующего согласования.
CREATE TABLE change_settings (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant (id),
    doc        jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);

ALTER TABLE change_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_settings
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- CHG-07: заседания CAB, повестка, голоса и решения.
CREATE TABLE cab_meeting (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    title            text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    starts_at        timestamptz NOT NULL,
    duration_minutes integer NOT NULL DEFAULT 60 CHECK (duration_minutes BETWEEN 5 AND 1440),
    location         text,
    chair_id         uuid NOT NULL,
    member_ids       uuid[] NOT NULL DEFAULT '{}',
    state            text NOT NULL DEFAULT 'planned' CHECK (state IN ('planned', 'in_progress', 'completed', 'cancelled')),
    minutes          text NOT NULL DEFAULT '' CHECK (char_length(minutes) <= 50000),
    started_at       timestamptz,
    completed_at     timestamptz,
    protocol_sent_at timestamptz,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL
);
CREATE INDEX cab_meeting_starts_idx ON cab_meeting (tenant_id, starts_at DESC, id DESC);

ALTER TABLE cab_meeting ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cab_meeting
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

CREATE TABLE cab_item (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    meeting_id  uuid NOT NULL REFERENCES cab_meeting (id) ON DELETE CASCADE,
    change_id   uuid NOT NULL REFERENCES change_detail (ticket_id) ON DELETE CASCADE,
    seq         integer NOT NULL,
    decision    text NOT NULL DEFAULT 'pending' CHECK (decision IN ('pending', 'approved', 'rejected', 'deferred')),
    comment     text,
    decided_by  uuid,
    decided_at  timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL,
    UNIQUE (meeting_id, change_id)
);
CREATE INDEX cab_item_change_idx ON cab_item (change_id);

ALTER TABLE cab_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cab_item
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

CREATE TABLE cab_vote (
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    item_id   uuid NOT NULL REFERENCES cab_item (id) ON DELETE CASCADE,
    voter_id  uuid NOT NULL,
    vote      text NOT NULL CHECK (vote IN ('approve', 'reject', 'abstain')),
    comment   text,
    voted_at  timestamptz NOT NULL,
    PRIMARY KEY (item_id, voter_id)
);

ALTER TABLE cab_vote ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cab_vote
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
