-- Крупные инциденты (MIM-01…05, 10.9 ТЗ): командный центр поверх заявки-инцидента модуля tickets.

CREATE TABLE mim_incident (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    ticket_id      uuid NOT NULL,
    -- requested — объявил агент, ждёт согласования LEAD или MIM; active; restored — сервис восстановлен; closed.
    state          text NOT NULL CHECK (state IN ('requested', 'active', 'restored', 'closed', 'rejected')),
    public_title   text NOT NULL CHECK (char_length(public_title) BETWEEN 1 AND 255),
    reason         text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 2000),
    hypothesis     text NOT NULL DEFAULT '' CHECK (char_length(hypothesis) <= 5000),
    service_ids    uuid[] NOT NULL DEFAULT '{}',
    declared_by    uuid NOT NULL,
    declared_at    timestamptz NOT NULL,
    approved_by    uuid,
    approved_at    timestamptz,
    restored_at    timestamptz,
    closed_at      timestamptz,
    next_update_at timestamptz,
    pir            text NOT NULL DEFAULT '' CHECK (char_length(pir) <= 100000),
    problem_id     uuid,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);
-- Один открытый крупный инцидент на заявку; отклонённый и закрытый не мешают объявить снова.
CREATE UNIQUE INDEX mim_incident_ticket ON mim_incident (tenant_id, ticket_id)
    WHERE state IN ('requested', 'active', 'restored');
CREATE INDEX mim_incident_state ON mim_incident (tenant_id, state, declared_at DESC);

CREATE TABLE mim_role (
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    mim_id      uuid NOT NULL REFERENCES mim_incident (id),
    role        text NOT NULL CHECK (role IN ('manager', 'tech_lead', 'comms', 'scribe')),
    user_id     uuid NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, mim_id, role)
);

-- Хронология (MIM-02): только дополняется. at — время события, его можно уточнить при записи.
CREATE TABLE mim_event (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    mim_id      uuid NOT NULL REFERENCES mim_incident (id),
    kind        text NOT NULL,
    at          timestamptz NOT NULL,
    text        text NOT NULL DEFAULT '' CHECK (char_length(text) <= 5000),
    actor_id    uuid,
    meta        jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0
);
CREATE INDEX mim_event_mim ON mim_event (tenant_id, mim_id, at, id);

CREATE TABLE mim_task (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    mim_id      uuid NOT NULL REFERENCES mim_incident (id),
    title       text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 500),
    assignee_id uuid,
    done        boolean NOT NULL DEFAULT false,
    done_at     timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE INDEX mim_task_mim ON mim_task (tenant_id, mim_id, created_at);

-- Опубликованные сообщения (MIM-04): пользователям или руководству, при желании — на странице статуса.
CREATE TABLE mim_update (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    mim_id         uuid NOT NULL REFERENCES mim_incident (id),
    audience       text NOT NULL CHECK (audience IN ('users', 'management')),
    title          text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    body           text NOT NULL CHECK (char_length(body) <= 10000),
    status_page    boolean NOT NULL DEFAULT false,
    next_update_at timestamptz,
    published_at   timestamptz NOT NULL,
    published_by   uuid,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL DEFAULT 0
);
CREATE INDEX mim_update_mim ON mim_update (tenant_id, mim_id, published_at DESC);

-- Шаблоны сообщений с переменными {{number}}, {{title}}, {{services}}, {{status}}, {{hypothesis}}, {{nextUpdate}}.
CREATE TABLE mim_template (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    audience    text NOT NULL CHECK (audience IN ('users', 'management')),
    title       text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    body        text NOT NULL CHECK (char_length(body) <= 10000),
    position    int NOT NULL DEFAULT 0,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);

-- Цепочки оповещения дежурных (MIM-03): шаги {groupId | userId, waitMinutes}.
CREATE TABLE mim_chain (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    steps       jsonb NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);

-- Оповещение по цепочке: текущий шаг, срок перехода к следующему, подтверждение.
CREATE TABLE mim_page (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    mim_id        uuid NOT NULL REFERENCES mim_incident (id),
    chain_id      uuid,
    name          text NOT NULL,
    steps         jsonb NOT NULL,
    channel       text NOT NULL CHECK (channel IN ('center', 'email')),
    step          int NOT NULL DEFAULT 0,
    state         text NOT NULL CHECK (state IN ('paging', 'acknowledged', 'exhausted', 'cancelled')),
    next_step_at  timestamptz,
    acked_by      uuid,
    acked_at      timestamptz,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE INDEX mim_page_due ON mim_page (next_step_at) WHERE state = 'paging';

CREATE TABLE mim_page_target (
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    page_id     uuid NOT NULL REFERENCES mim_page (id),
    step        int NOT NULL,
    user_id     uuid NOT NULL,
    notified_at timestamptz NOT NULL,
    acked_at    timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, page_id, step, user_id)
);

ALTER TABLE mim_incident ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_incident USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_role ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_role USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_event USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_task ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_task USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_update ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_update USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_template USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_chain ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_chain USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_page ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_page USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE mim_page_target ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mim_page_target USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Оповещения, у которых истёк срок шага, во всех арендаторах (для роли worker), наружу — только идентификаторы.
CREATE FUNCTION mim_due_pages(p_now timestamptz, p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT p.tenant_id, p.id FROM mim_page p
     WHERE p.state = 'paging' AND p.next_step_at <= p_now
     ORDER BY p.next_step_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION mim_due_pages(timestamptz, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mim_due_pages(timestamptz, int) TO ${app_role};
