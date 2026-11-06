-- CMDB: источники данных, загрузка пакетами, сверка и слияние дубликатов (CFG-04, CFG-05, раздел 4.9 ТЗ).

-- Слитый дубликат остаётся выведенным из эксплуатации со ссылкой на КЕ, в которую он слит: ссылки других
-- модулей на него не ломаются, история сохраняется.
ALTER TABLE ci ADD COLUMN merged_into uuid REFERENCES ci (id);
CREATE INDEX ci_merged_into_idx ON ci (merged_into) WHERE merged_into IS NOT NULL;

-- Какая КЕ объявила связь в пакете источника: связи объекта заменяются при следующей загрузке этого объекта,
-- связи, объявленные другими КЕ, не трогаются.
ALTER TABLE ci_relation ADD COLUMN origin_ci_id uuid REFERENCES ci (id) ON DELETE SET NULL;
CREATE INDEX ci_relation_origin_idx ON ci_relation (origin, origin_ci_id) WHERE valid_to IS NULL;

-- Источник данных: агент обнаружения, импорт, коннектор. Приоритет 1 — самый доверенный, ручной ввод — 10.
CREATE TABLE ci_source (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    code       text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_-]{1,62}$' AND code <> 'manual'),
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    kind       text NOT NULL CHECK (kind IN ('import', 'api', 'discovery', 'kubernetes', 'cloud')),
    priority   integer NOT NULL CHECK (priority BETWEEN 1 AND 100),
    stale_days integer NOT NULL CHECK (stale_days BETWEEN 1 AND 3650),
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, code)
);

-- Объект источника и КЕ, с которой он связан. missing_since — не пришёл в последнем полном обходе,
-- removed_at — источник сообщил, что объекта больше нет.
CREATE TABLE ci_source_record (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    source_id      uuid NOT NULL REFERENCES ci_source (id),
    external_id    text NOT NULL CHECK (char_length(external_id) BETWEEN 1 AND 255),
    ci_id          uuid NOT NULL REFERENCES ci (id) ON DELETE CASCADE,
    first_seen_at  timestamptz NOT NULL,
    last_seen_at   timestamptz NOT NULL,
    last_run_id    text,
    missing_since  timestamptz,
    removed_at     timestamptz,
    UNIQUE (source_id, external_id)
);
CREATE INDEX ci_source_record_ci_idx ON ci_source_record (ci_id);
CREATE INDEX ci_source_record_run_idx ON ci_source_record (source_id, last_run_id) WHERE removed_at IS NULL;

-- Принятый пакет и его результат: повтор с тем же batchId возвращает сохранённый ответ.
CREATE TABLE ci_ingest_batch (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    source_id  uuid NOT NULL REFERENCES ci_source (id),
    batch_key  text NOT NULL,
    run_id     text,
    final      boolean NOT NULL,
    received   integer NOT NULL,
    created    integer NOT NULL,
    updated    integer NOT NULL,
    unchanged  integer NOT NULL,
    conflicts  integer NOT NULL,
    errors     integer NOT NULL,
    removed    integer NOT NULL,
    results    jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    UNIQUE (source_id, batch_key)
);
CREATE INDEX ci_ingest_batch_source_idx ON ci_ingest_batch (source_id, created_at DESC);

-- CFG-05: расхождения, которые сверка не решила сама. attribute — источник с меньшим доверием предлагает
-- другое значение атрибута; duplicate — объект подходит под несколько КЕ.
CREATE TABLE ci_conflict (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    kind           text NOT NULL CHECK (kind IN ('attribute', 'duplicate')),
    status         text NOT NULL CHECK (status IN ('open', 'accepted', 'ignored', 'merged', 'dismissed')),
    source_id      uuid NOT NULL REFERENCES ci_source (id),
    external_id    text NOT NULL,
    ci_id          uuid REFERENCES ci (id) ON DELETE CASCADE,
    attribute      text,
    current_value  jsonb,
    proposed_value jsonb,
    current_source text,
    candidate_ids  uuid[] NOT NULL DEFAULT '{}',
    -- объект из пакета для duplicate: после выбора КЕ он применяется к ней
    item           jsonb,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    resolved_at    timestamptz,
    resolved_by    uuid,
    version        bigint NOT NULL,
    CHECK (kind <> 'attribute' OR (ci_id IS NOT NULL AND attribute IS NOT NULL))
);
CREATE UNIQUE INDEX ci_conflict_attribute_open_uq ON ci_conflict (source_id, ci_id, attribute)
    WHERE kind = 'attribute' AND status = 'open';
CREATE UNIQUE INDEX ci_conflict_duplicate_open_uq ON ci_conflict (source_id, external_id)
    WHERE kind = 'duplicate' AND status = 'open';
CREATE INDEX ci_conflict_list_idx ON ci_conflict (tenant_id, status, created_at DESC, id DESC);
CREATE INDEX ci_conflict_ci_idx ON ci_conflict (ci_id);

ALTER TABLE ci_source ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_source
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_source_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_source_record
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_ingest_batch ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_ingest_batch
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_conflict ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_conflict
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
