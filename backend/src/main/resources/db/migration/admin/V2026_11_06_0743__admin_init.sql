-- Модуль «Управление» (ADM-01…04, 06; раздел 10.16 ТЗ): мастер настройки, подразделения, справочники,
-- дежурства, версии настроек и импорт CSV. Пользователи, группы, графики, категории и статусы живут в своих
-- модулях: здесь только то, чем владеет «Управление».

-- Состояние мастера первичной настройки: одна строка на арендатора.
CREATE TABLE setup_state (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL UNIQUE REFERENCES tenant (id),
    org_name       text NOT NULL DEFAULT '' CHECK (char_length(org_name) <= 255),
    timezone       text NOT NULL DEFAULT 'Europe/Moscow' CHECK (char_length(timezone) <= 64),
    locale         text NOT NULL DEFAULT 'ru' CHECK (locale IN ('ru', 'en')),
    steps          jsonb NOT NULL DEFAULT '{}'::jsonb,
    completed_at   timestamptz,
    demo_loaded_at timestamptz,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);

-- Организации и подразделения (ADM-02). На них ссылаются organization_id и department_id пользователя.
CREATE TABLE org_unit (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    kind       text NOT NULL CHECK (kind IN ('organization', 'department')),
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    code       text CHECK (code IS NULL OR char_length(code) BETWEEN 1 AND 64),
    parent_id  uuid REFERENCES org_unit (id) ON DELETE RESTRICT,
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX org_unit_name_uq ON org_unit
    (tenant_id, kind, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE UNIQUE INDEX org_unit_code_uq ON org_unit (tenant_id, lower(code)) WHERE code IS NOT NULL;
CREATE INDEX org_unit_parent_idx ON org_unit (tenant_id, parent_id);

-- Справочники «Управления»: коды решения, приостановки и отмены (ADM-03). Документ на вид, без строки —
-- значения по умолчанию из кода.
CREATE TABLE admin_dictionary (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    kind       text NOT NULL CHECK (kind IN ('resolution', 'pending', 'cancel')),
    items      jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, kind)
);

-- Дежурства группы: кто принимает заявки группы в интервал времени (10.16 «Назначить дежурство»).
CREATE TABLE duty_shift (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    group_id   uuid NOT NULL,
    user_id    uuid NOT NULL,
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL,
    note       text NOT NULL DEFAULT '' CHECK (char_length(note) <= 500),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (ends_at > starts_at)
);
CREATE INDEX duty_shift_group_idx ON duty_shift (tenant_id, group_id, starts_at);

-- Версии настроек и журнал действий администратора (ADM-06). Только добавление: строки не меняются.
CREATE TABLE config_version (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    entity     text NOT NULL CHECK (entity ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*$' AND char_length(entity) <= 100),
    entity_id  text NOT NULL DEFAULT '' CHECK (char_length(entity_id) <= 100),
    label      text NOT NULL DEFAULT '' CHECK (char_length(label) <= 255),
    version_no integer NOT NULL CHECK (version_no > 0),
    action     text NOT NULL CHECK (action IN ('create', 'update', 'delete', 'restore', 'import', 'rollback', 'setup')),
    snapshot   jsonb NOT NULL,
    comment    text NOT NULL DEFAULT '' CHECK (char_length(comment) <= 1000),
    source     text NOT NULL DEFAULT 'server' CHECK (source IN ('server', 'ui')),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, entity, entity_id, version_no)
);
CREATE INDEX config_version_journal_idx ON config_version (tenant_id, created_at DESC, id DESC);

-- Импорт CSV (ADM-04): разобранные строки с ошибками для предпросмотра и созданные записи для отката.
CREATE TABLE import_job (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    kind           text NOT NULL CHECK (kind IN ('users', 'groups', 'departments')),
    status         text NOT NULL CHECK (status IN ('preview', 'applied', 'rolled_back')),
    file_name      text NOT NULL DEFAULT '' CHECK (char_length(file_name) <= 255),
    total_rows     integer NOT NULL DEFAULT 0,
    valid_rows     integer NOT NULL DEFAULT 0,
    rows           jsonb NOT NULL,
    created_refs   jsonb NOT NULL DEFAULT '[]'::jsonb,
    applied_at     timestamptz,
    rolled_back_at timestamptz,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);
CREATE INDEX import_job_list_idx ON import_job (tenant_id, created_at DESC, id DESC);

ALTER TABLE setup_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_dictionary ENABLE ROW LEVEL SECURITY;
ALTER TABLE duty_shift ENABLE ROW LEVEL SECURITY;
ALTER TABLE config_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_job ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON setup_state
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_isolation ON org_unit
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_isolation ON admin_dictionary
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_isolation ON duty_shift
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_isolation ON config_version
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
CREATE POLICY tenant_isolation ON import_job
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Журнал только дописывается: роль приложения не меняет и не удаляет версии.
REVOKE UPDATE, DELETE ON config_version FROM ${app_role};
