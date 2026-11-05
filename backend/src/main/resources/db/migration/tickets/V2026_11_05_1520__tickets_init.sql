-- Модуль tickets: заявки всех типов (TCK), инциденты (INC), настройки циклов, полей и маршрутизации.
-- Все таблицы с данными арендатора: tenant_id, служебные поля, RLS с политикой tenant_isolation.

-- Заявка. Статус — код из цикла типа (настройка workflow.<type>), категория статуса денормализована
-- для запросов и других модулей. Значения настраиваемых полей — в custom (JSONB, TCK-07).
CREATE TABLE ticket (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenant (id),
    number                  text NOT NULL,
    type                    text NOT NULL CHECK (type IN ('incident', 'request', 'problem', 'change', 'task')),
    title                   text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    description             text NOT NULL DEFAULT '',
    status                  text NOT NULL,
    status_category         text NOT NULL CHECK (status_category IN
                                ('draft', 'new', 'approval', 'assigned', 'in_progress', 'pending', 'resolved', 'closed', 'cancelled')),
    priority                text NOT NULL CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    impact                  text NOT NULL CHECK (impact IN ('high', 'medium', 'low')),
    urgency                 text NOT NULL CHECK (urgency IN ('high', 'medium', 'low')),
    priority_manual         boolean NOT NULL DEFAULT false,
    requester_id            uuid NOT NULL,
    created_for_id          uuid,
    organization_id         uuid,
    group_id                uuid,
    assignee_id             uuid,
    service_id              uuid,
    ci_id                   uuid,
    category_id             uuid,
    catalog_item_id         uuid,
    parent_id               uuid REFERENCES ticket (id),
    channel                 text NOT NULL CHECK (channel IN ('portal', 'email', 'api', 'chat', 'monitoring', 'agent')),
    confidential            boolean NOT NULL DEFAULT false,
    security_group_id       uuid,
    due_at                  timestamptz,
    pending_reason          text,
    pending_until           timestamptz,
    resolution_code         text,
    resolution_text         text,
    reopen_count            int NOT NULL DEFAULT 0,
    escalation_level        int NOT NULL DEFAULT 0,
    identity_check_required boolean NOT NULL DEFAULT false,
    identity_verified_at    timestamptz,
    identity_verified_by    uuid,
    identity_method         text,
    first_response_at       timestamptz,
    resolved_at             timestamptz,
    closed_at               timestamptz,
    sla_due_at              timestamptz,
    awaiting_agent          boolean NOT NULL DEFAULT false,
    custom                  jsonb NOT NULL DEFAULT '{}',
    -- Резервный поиск PostgreSQL с русской морфологией (TCK-15), основной — OpenSearch.
    search_vector           tsvector GENERATED ALWAYS AS (
                                setweight(to_tsvector('russian', number || ' ' || title), 'A')
                                || setweight(to_tsvector('russian', description), 'B')) STORED,
    created_at              timestamptz NOT NULL,
    created_by              uuid,
    updated_at              timestamptz NOT NULL,
    updated_by              uuid,
    version                 bigint NOT NULL,
    UNIQUE (tenant_id, number)
);
CREATE INDEX ticket_queue_idx ON ticket (tenant_id, status_category, group_id, priority);
CREATE INDEX ticket_status_idx ON ticket (tenant_id, status, group_id, priority);
CREATE INDEX ticket_assignee_idx ON ticket (tenant_id, assignee_id, status_category);
CREATE INDEX ticket_requester_idx ON ticket (tenant_id, requester_id, created_at DESC);
CREATE INDEX ticket_created_for_idx ON ticket (tenant_id, created_for_id) WHERE created_for_id IS NOT NULL;
CREATE INDEX ticket_created_idx ON ticket (tenant_id, created_at DESC, id DESC);
CREATE INDEX ticket_updated_idx ON ticket (tenant_id, updated_at DESC, id DESC);
CREATE INDEX ticket_sla_due_idx ON ticket (tenant_id, sla_due_at, id) WHERE status_category NOT IN ('closed', 'cancelled');
CREATE INDEX ticket_parent_idx ON ticket (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX ticket_resolved_idx ON ticket (resolved_at) WHERE status_category = 'resolved';
CREATE INDEX ticket_pending_idx ON ticket (pending_until) WHERE status_category = 'pending';
CREATE INDEX ticket_custom_idx ON ticket USING gin (custom jsonb_path_ops);
CREATE INDEX ticket_search_idx ON ticket USING gin (search_vector);

-- Счётчики номеров по типу (TCK-02): INC-000123.
CREATE TABLE ticket_number_seq (
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    type       text NOT NULL,
    last_value bigint NOT NULL,
    PRIMARY KEY (tenant_id, type)
);

-- Лента (TCK-03): ответы, заметки, системные события, изменения полей. Только добавление.
CREATE TABLE ticket_activity (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    ticket_id      uuid NOT NULL REFERENCES ticket (id),
    kind           text NOT NULL CHECK (kind IN ('reply', 'note', 'system', 'change')),
    visibility     text NOT NULL CHECK (visibility IN ('public', 'internal')),
    author_id      uuid,
    from_requester boolean NOT NULL DEFAULT false,
    body           text NOT NULL DEFAULT '',
    meta           jsonb NOT NULL DEFAULT '{}',
    mentions       uuid[] NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);
CREATE INDEX ticket_activity_ticket_idx ON ticket_activity (ticket_id, id);

-- История изменений полей: кто, что, когда, причина.
CREATE TABLE ticket_field_history (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    ticket_id  uuid NOT NULL REFERENCES ticket (id),
    field      text NOT NULL,
    old_value  jsonb,
    new_value  jsonb,
    reason     text,
    actor_id   uuid,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);
CREATE INDEX ticket_field_history_ticket_idx ON ticket_field_history (ticket_id, id);

-- Связи (TCK-05). Хранится одна каноническая запись: parent_of, duplicate_of, blocks, caused_by, relates_to.
CREATE TABLE ticket_link (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    source_id  uuid NOT NULL REFERENCES ticket (id),
    target_id  uuid NOT NULL REFERENCES ticket (id),
    kind       text NOT NULL CHECK (kind IN ('parent_of', 'duplicate_of', 'blocks', 'caused_by', 'relates_to')),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (source_id <> target_id),
    UNIQUE (tenant_id, source_id, target_id, kind)
);
CREATE INDEX ticket_link_target_idx ON ticket_link (target_id);

-- Наблюдатели (TCK-10).
CREATE TABLE ticket_watcher (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    ticket_id  uuid NOT NULL REFERENCES ticket (id),
    user_id    uuid NOT NULL,
    reason     text NOT NULL CHECK (reason IN ('self', 'added', 'mention', 'merge', 'creator')),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (ticket_id, user_id)
);
CREATE INDEX ticket_watcher_user_idx ON ticket_watcher (tenant_id, user_id);

-- Определения настраиваемых полей (TCK-07).
CREATE TABLE ticket_custom_field (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    code         text NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{1,62}$'),
    name         text NOT NULL,
    field_type   text NOT NULL CHECK (field_type IN ('text', 'number', 'date', 'list', 'user', 'group', 'ci', 'checkbox', 'file')),
    options      jsonb NOT NULL DEFAULT '[]',
    multiple     boolean NOT NULL DEFAULT false,
    required     boolean NOT NULL DEFAULT false,
    ticket_types text[] NOT NULL DEFAULT '{}',
    active       boolean NOT NULL DEFAULT true,
    position     int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL,
    UNIQUE (tenant_id, code)
);

-- Категории заявок (дерево).
CREATE TABLE ticket_category (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    parent_id    uuid REFERENCES ticket_category (id),
    name         text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    ticket_types text[] NOT NULL DEFAULT '{}',
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL
);

-- Правила маршрутизации (5.8), применяются по возрастанию position.
CREATE TABLE ticket_routing_rule (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL,
    position    int NOT NULL DEFAULT 0,
    enabled     boolean NOT NULL DEFAULT true,
    conditions  jsonb NOT NULL DEFAULT '{}',
    group_id    uuid NOT NULL,
    assignee_id uuid,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE INDEX ticket_routing_rule_order_idx ON ticket_routing_rule (tenant_id, position, id);

-- Распределение внутри группы: по кругу или по наименьшей загрузке.
CREATE TABLE ticket_group_dispatch (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    group_id   uuid NOT NULL,
    strategy   text NOT NULL CHECK (strategy IN ('none', 'round_robin', 'least_loaded')),
    members    uuid[] NOT NULL DEFAULT '{}',
    rr_cursor  int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, group_id)
);

-- Настройки модуля документами: workflow.<type>, priority_matrix, general. Нет строки — значения по умолчанию.
CREATE TABLE ticket_settings (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    key        text NOT NULL,
    value      jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, key)
);

-- Массовые операции и данные для отмены в течение 10 секунд (TCK-12).
CREATE TABLE ticket_bulk_operation (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    actor_id   uuid NOT NULL,
    action     text NOT NULL,
    params     jsonb NOT NULL DEFAULT '{}',
    snapshot   jsonb NOT NULL DEFAULT '[]',
    undo_until timestamptz NOT NULL,
    undone_at  timestamptz,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);

-- Сохранённые представления очереди (TCK-16).
CREATE TABLE ticket_view (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    owner_id   uuid NOT NULL,
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    visibility text NOT NULL CHECK (visibility IN ('personal', 'group', 'shared')),
    group_id   uuid,
    filter     jsonb NOT NULL DEFAULT '{}',
    sort       text,
    columns    jsonb NOT NULL DEFAULT '[]',
    position   int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (visibility <> 'group' OR group_id IS NOT NULL)
);
CREATE INDEX ticket_view_owner_idx ON ticket_view (tenant_id, owner_id);

-- Готовые ответы (TCK-09).
CREATE TABLE ticket_response_template (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    owner_id     uuid NOT NULL,
    name         text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    body         text NOT NULL,
    visibility   text NOT NULL CHECK (visibility IN ('personal', 'group', 'shared')),
    group_id     uuid,
    ticket_types text[] NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL,
    CHECK (visibility <> 'group' OR group_id IS NOT NULL)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['ticket', 'ticket_number_seq', 'ticket_activity', 'ticket_field_history', 'ticket_link',
                             'ticket_watcher', 'ticket_custom_field', 'ticket_category', 'ticket_routing_rule',
                             'ticket_group_dispatch', 'ticket_settings', 'ticket_bulk_operation', 'ticket_view',
                             'ticket_response_template']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

-- Лента и история только дополняются (TCK-03, аудит изменений).
REVOKE UPDATE, DELETE ON ticket_activity, ticket_field_history FROM ${app_role};

-- Таймеры по всем арендаторам для фонового обработчика: решённые заявки к автозакрытию (INC-07)
-- и ожидания с истёкшим сроком возврата. SECURITY DEFINER возвращает только идентификаторы,
-- сама обработка идёт в арендаторе заявки через TenantContext.runAs и RLS.
CREATE FUNCTION tickets_due_timers(resolved_before timestamptz, max_rows int)
    RETURNS TABLE (tenant_id uuid, ticket_id uuid, timer text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    (SELECT t.tenant_id, t.id, 'autoclose' FROM ticket t
      WHERE t.status_category = 'resolved' AND t.resolved_at < resolved_before
      ORDER BY t.resolved_at LIMIT max_rows)
    UNION ALL
    (SELECT t.tenant_id, t.id, 'pending_return' FROM ticket t
      WHERE t.status_category = 'pending' AND t.pending_until < now()
      ORDER BY t.pending_until LIMIT max_rows)
$$;
REVOKE ALL ON FUNCTION tickets_due_timers(timestamptz, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tickets_due_timers(timestamptz, int) TO ${app_role};
