-- Каталог услуг (REQ-01), формы предложений (REQ-02), согласования (REQ-03).
-- JSON-структуры (схема формы, шаги маршрута) хранятся в text с проверкой IS JSON (PostgreSQL 16+):
-- модуль читает их целиком и не фильтрует по содержимому. Переход на jsonb — обратно совместимой миграцией.

CREATE TABLE catalog_category (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    parent_id   uuid REFERENCES catalog_category (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    description text NOT NULL DEFAULT '',
    icon        text,
    sort_order  int  NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL,
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX catalog_category_parent_idx ON catalog_category (tenant_id, parent_id, sort_order);

-- Маршрут согласования: упорядоченные шаги, у шага режим all/any/quorum, согласующие и условие.
CREATE TABLE approval_flow (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    description text NOT NULL DEFAULT '',
    steps       text NOT NULL DEFAULT '[]' CHECK (steps IS JSON ARRAY),
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);

-- Предложение каталога (услуга, которую можно заказать). visible_roles пустой — видно всем.
CREATE TABLE catalog_item (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    category_id      uuid NOT NULL REFERENCES catalog_category (id),
    kind             text NOT NULL DEFAULT 'request' CHECK (kind IN ('request', 'incident')),
    name             text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 160),
    summary          text NOT NULL DEFAULT '' CHECK (char_length(summary) <= 300),
    description      text NOT NULL DEFAULT '',
    icon             text,
    team             text,
    keywords         text NOT NULL DEFAULT '',
    form             text NOT NULL DEFAULT '{"fields":[]}' CHECK (form IS JSON OBJECT),
    approval_flow_id uuid REFERENCES approval_flow (id),
    visible_roles    text[] NOT NULL DEFAULT '{}',
    allow_on_behalf  boolean NOT NULL DEFAULT true,
    cost             numeric(14, 2) CHECK (cost IS NULL OR cost >= 0),
    currency         text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    lead_time_hours  int CHECK (lead_time_hours IS NULL OR lead_time_hours > 0),
    order_count      bigint NOT NULL DEFAULT 0,
    sort_order       int NOT NULL DEFAULT 0,
    active           boolean NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL
);
CREATE INDEX catalog_item_category_idx ON catalog_item (tenant_id, category_id, sort_order, name);
CREATE INDEX catalog_item_popular_idx ON catalog_item (tenant_id, order_count DESC) WHERE active;

-- Избранные услуги пользователя (10.4).
CREATE TABLE catalog_favorite (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL,
    item_id    uuid NOT NULL REFERENCES catalog_item (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, user_id, item_id)
);

-- Маршрут согласования конкретной заявки. Шаги копируются из шаблона на момент заказа,
-- чтобы правка шаблона не меняла уже идущие согласования.
CREATE TABLE approval_request (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    ticket_id      uuid NOT NULL,
    ticket_number  text NOT NULL,
    title          text NOT NULL,
    item_id        uuid REFERENCES catalog_item (id),
    flow_id        uuid REFERENCES approval_flow (id),
    requester_id   uuid NOT NULL,
    created_for_id uuid,
    steps          text NOT NULL CHECK (steps IS JSON ARRAY),
    current_step   int NOT NULL DEFAULT 0,
    state          text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'approved', 'rejected', 'cancelled')),
    decided_at     timestamptz,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL,
    UNIQUE (tenant_id, ticket_id)
);

-- Решение одного согласующего по шагу (7.3 ТЗ: approval).
CREATE TABLE approval (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    request_id   uuid NOT NULL REFERENCES approval_request (id),
    ticket_id    uuid NOT NULL,
    step_index   int NOT NULL,
    step_name    text NOT NULL,
    approver_id  uuid NOT NULL,
    delegate_from uuid,
    decision     text NOT NULL DEFAULT 'pending'
                 CHECK (decision IN ('pending', 'approved', 'rejected', 'skipped', 'delegated', 'cancelled')),
    comment      text,
    info_requested_at timestamptz,
    due_at       timestamptz,
    decided_at   timestamptz,
    auth_method  text,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL
);
CREATE INDEX approval_approver_idx ON approval (tenant_id, approver_id, decision, created_at DESC, id DESC);
CREATE INDEX approval_request_idx ON approval (request_id, step_index);

ALTER TABLE catalog_category ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_category
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE approval_flow ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON approval_flow
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE catalog_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_item
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE catalog_favorite ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_favorite
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE approval_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON approval_request
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE approval ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON approval
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
