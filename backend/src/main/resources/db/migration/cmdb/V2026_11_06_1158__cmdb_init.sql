-- CMDB (раздел 4.9 и 7.5 ТЗ): классы КЕ, типы связей, КЕ, связи, история.
-- Классы и типы связей по умолчанию модуль создаёт для арендатора сам при первом обращении (CmdbDefaults).

-- Поиск КЕ по части имени на 200 000 записей (раздел 1.4 ТЗ). pg_trgm — доверенное расширение,
-- владелец базы создаёт его без прав суперпользователя.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- CFG-01, CFG-02: иерархия классов с наследованием атрибутов, уровень сервисной модели.
CREATE TABLE ci_class (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant (id),
    parent_id            uuid REFERENCES ci_class (id),
    code                 text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,62}$'),
    name                 text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    layer                text NOT NULL CHECK (layer IN ('business_service', 'technical_service', 'application',
                                                        'infrastructure')),
    abstract             boolean NOT NULL DEFAULT false,
    system               boolean NOT NULL DEFAULT false,
    active               boolean NOT NULL DEFAULT true,
    -- [{"code","name","type","required","options"}], наследуются подклассами
    attributes           jsonb NOT NULL DEFAULT '[]',
    -- [["serial_number"], ["hostname", "domain"]]: наборы атрибутов по порядку; пусто — правила родителя
    identification_rules jsonb NOT NULL DEFAULT '[]',
    created_at           timestamptz NOT NULL,
    created_by           uuid,
    updated_at           timestamptz NOT NULL,
    updated_by           uuid,
    version              bigint NOT NULL,
    UNIQUE (tenant_id, code),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX ci_class_parent_idx ON ci_class (parent_id);

-- CFG-03: типы связей. Направление влияния: target_to_source — отказ цели влияет на источник
-- (приложение «работает на» сервере), source_to_target — наоборот (кластер «размещает» ВМ), none — не влияет.
CREATE TABLE ci_relation_type (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    code             text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,62}$'),
    name             text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    inverse_name     text NOT NULL CHECK (char_length(inverse_name) BETWEEN 1 AND 255),
    impact_direction text NOT NULL CHECK (impact_direction IN ('target_to_source', 'source_to_target', 'none')),
    system           boolean NOT NULL DEFAULT false,
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL,
    UNIQUE (tenant_id, code)
);

CREATE TABLE ci (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenant (id),
    class_id          uuid NOT NULL REFERENCES ci_class (id),
    name              text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    status            text NOT NULL CHECK (status IN ('planned', 'active', 'maintenance', 'retired')),
    environment       text CHECK (environment ~ '^[a-z][a-z0-9_]{0,31}$'),
    criticality       text NOT NULL DEFAULT 'medium' CHECK (criticality IN ('low', 'medium', 'high', 'critical')),
    owner_group_id    uuid,
    description       text NOT NULL DEFAULT '',
    attributes        jsonb NOT NULL DEFAULT '{}',
    -- CFG-05: источник каждого атрибута {"hostname": "discovery"}, по нему работает приоритет источников
    attribute_sources jsonb NOT NULL DEFAULT '{}',
    -- CFG-07: когда КЕ последний раз видел источник данных (актуальность)
    last_seen_at      timestamptz,
    created_at        timestamptz NOT NULL,
    created_by        uuid,
    updated_at        timestamptz NOT NULL,
    updated_by        uuid,
    version           bigint NOT NULL
);
CREATE INDEX ci_class_idx ON ci (tenant_id, class_id);
CREATE INDEX ci_name_idx ON ci (tenant_id, lower(name), id);
CREATE INDEX ci_name_trgm_idx ON ci USING gin (lower(name) gin_trgm_ops);
-- Идентификация при сверке: attributes @> '{"serial_number": "..."}'
CREATE INDEX ci_attributes_idx ON ci USING gin (attributes jsonb_path_ops);
CREATE INDEX ci_owner_group_idx ON ci (tenant_id, owner_group_id) WHERE owner_group_id IS NOT NULL;

-- Связь действует с valid_from до valid_to; снятая связь остаётся для состояния на дату (CFG-06).
CREATE TABLE ci_relation (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    type_id    uuid NOT NULL REFERENCES ci_relation_type (id),
    source_id  uuid NOT NULL REFERENCES ci (id) ON DELETE CASCADE,
    target_id  uuid NOT NULL REFERENCES ci (id) ON DELETE CASCADE,
    -- кто завёл связь: manual или код источника данных
    origin     text NOT NULL DEFAULT 'manual',
    valid_from timestamptz NOT NULL,
    valid_to   timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (source_id <> target_id),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX ci_relation_active_uq ON ci_relation (tenant_id, type_id, source_id, target_id)
    WHERE valid_to IS NULL;
CREATE INDEX ci_relation_source_idx ON ci_relation (source_id) WHERE valid_to IS NULL;
CREATE INDEX ci_relation_target_idx ON ci_relation (target_id) WHERE valid_to IS NULL;
CREATE INDEX ci_relation_history_idx ON ci_relation (tenant_id, source_id, target_id, valid_from);

-- CFG-06: история полей КЕ. Значения в JSON, чтобы хранить строки, числа и атрибуты одинаково.
CREATE TABLE ci_history (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    ci_id     uuid NOT NULL REFERENCES ci (id) ON DELETE CASCADE,
    field     text NOT NULL,
    old_value jsonb,
    new_value jsonb,
    source    text NOT NULL DEFAULT 'manual',
    actor_id  uuid,
    at        timestamptz NOT NULL
);
CREATE INDEX ci_history_ci_idx ON ci_history (ci_id, at DESC, id DESC);

ALTER TABLE ci_class ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_class
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_relation_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_relation_type
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_relation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_relation
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE ci_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_history
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
