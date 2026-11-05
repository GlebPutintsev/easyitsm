-- SLA-движок (SLA-01…06, разделы 5.3 и 7.3 ТЗ): графики, праздники, политики, цели, счётчики, паузы, журнал.

-- Рабочий график: часовой пояс и часы по дням недели (SLA-03).
CREATE TABLE work_schedule (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    timezone   text NOT NULL,
    -- Семь строк с понедельника по воскресенье: '09:00-13:00,14:00-18:00', пусто — выходной.
    week       text[] NOT NULL CHECK (cardinality(week) = 7),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);
CREATE INDEX work_schedule_tenant_idx ON work_schedule (tenant_id, id);

-- Исключения графика по датам: праздник (пустые часы), сокращённый или перенесённый рабочий день.
CREATE TABLE holiday (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    schedule_id uuid NOT NULL REFERENCES work_schedule (id) ON DELETE CASCADE,
    day         date NOT NULL,
    name        text NOT NULL DEFAULT '',
    hours       text NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL,
    UNIQUE (schedule_id, day)
);

-- Политика SLA, OLA или UC с условиями применения (SLA-01, SLA-02). Пустой массив условия — любое значение.
CREATE TABLE sla_policy (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    name           text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    description    text NOT NULL DEFAULT '',
    kind           text NOT NULL CHECK (kind IN ('sla', 'ola', 'uc')),
    active         boolean NOT NULL DEFAULT true,
    rank           int NOT NULL DEFAULT 100,
    schedule_id    uuid REFERENCES work_schedule (id),
    ticket_types   text[] NOT NULL DEFAULT '{}',
    priorities     text[] NOT NULL DEFAULT '{}',
    service_ids    uuid[] NOT NULL DEFAULT '{}',
    client_ids     uuid[] NOT NULL DEFAULT '{}',
    group_ids      uuid[] NOT NULL DEFAULT '{}',
    pause_statuses text[] NOT NULL DEFAULT '{pending}',
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);
CREATE INDEX sla_policy_tenant_idx ON sla_policy (tenant_id, kind, rank);

-- Цели политики: показатель, приоритет, срок, пороги и действия (SLA-01, SLA-05).
CREATE TABLE sla_target (
    policy_id      uuid NOT NULL REFERENCES sla_policy (id) ON DELETE CASCADE,
    seq            int NOT NULL,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    metric         text NOT NULL CHECK (metric IN ('response', 'resolution')),
    priority       text NOT NULL,
    amount         int NOT NULL CHECK (amount > 0),
    unit           text NOT NULL CHECK (unit IN ('minutes', 'hours', 'business_days')),
    calendar       text NOT NULL DEFAULT 'policy' CHECK (calendar IN ('policy', '24x7')),
    thresholds     int[] NOT NULL DEFAULT '{50,75,90}',
    warn_actions   text[] NOT NULL DEFAULT '{}',
    breach_actions text[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (policy_id, seq)
);

-- Состояние заявки для SLA: последовательная обработка снимков и отсев устаревших.
CREATE TABLE sla_ticket (
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    ticket_id      uuid NOT NULL,
    last_event_at  timestamptz,
    counting_since timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, ticket_id)
);

-- Счётчик по заявке и цели. Срок, пороги и нарушение вычисляются от started_at с вычетом пауз.
CREATE TABLE sla_instance (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    ticket_id        uuid NOT NULL,
    policy_id        uuid NOT NULL REFERENCES sla_policy (id),
    kind             text NOT NULL CHECK (kind IN ('sla', 'ola', 'uc')),
    metric           text NOT NULL CHECK (metric IN ('response', 'resolution')),
    priority         text NOT NULL,
    schedule_id      uuid REFERENCES work_schedule (id),
    target_ms        bigint NOT NULL CHECK (target_ms >= 0),
    thresholds       int[] NOT NULL DEFAULT '{}',
    warn_actions     text[] NOT NULL DEFAULT '{}',
    breach_actions   text[] NOT NULL DEFAULT '{}',
    state            text NOT NULL CHECK (state IN ('running', 'paused', 'completed', 'cancelled')),
    started_at       timestamptz NOT NULL,
    stopped_at       timestamptz,
    due_at           timestamptz,
    breached_at      timestamptz,
    next_check_at    timestamptz,
    fired_thresholds int[] NOT NULL DEFAULT '{}',
    end_reason       text,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    version          bigint NOT NULL
);
CREATE INDEX sla_instance_ticket_idx ON sla_instance (tenant_id, ticket_id);
CREATE INDEX sla_instance_next_check_idx ON sla_instance (next_check_at) WHERE next_check_at IS NOT NULL;
CREATE INDEX sla_instance_schedule_idx ON sla_instance (tenant_id, schedule_id) WHERE state IN ('running', 'paused');

-- Исключённые интервалы счётчика: паузы с причиной (SLA-04) и периоды в статусе «Решена».
CREATE TABLE sla_pause (
    instance_id uuid NOT NULL REFERENCES sla_instance (id) ON DELETE CASCADE,
    seq         int NOT NULL,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    kind        text NOT NULL CHECK (kind IN ('pause', 'resolved')),
    started_at  timestamptz NOT NULL,
    ended_at    timestamptz,
    reason      text,
    PRIMARY KEY (instance_id, seq),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- Журнал счётчика: старт, пауза, пересчёт со старым и новым сроком (SLA-06), порог, нарушение, завершение.
CREATE TABLE sla_instance_event (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    instance_id uuid NOT NULL REFERENCES sla_instance (id) ON DELETE CASCADE,
    ticket_id   uuid NOT NULL,
    type        text NOT NULL,
    occurred_at timestamptz NOT NULL,
    reason      text,
    from_value  text,
    to_value    text,
    old_due_at  timestamptz,
    new_due_at  timestamptz,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE INDEX sla_instance_event_instance_idx ON sla_instance_event (instance_id, id);

ALTER TABLE work_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_schedule
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE holiday ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON holiday
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_policy
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_target ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_target
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_ticket ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_ticket
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_instance ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_instance
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_pause ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_pause
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
ALTER TABLE sla_instance_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_instance_event
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Счётчики, у которых наступила проверка, во всех арендаторах. SECURITY DEFINER: фоновому обработчику
-- не нужен обход RLS, а наружу выходят только идентификаторы. Дальше каждый счётчик читается в своём арендаторе.
CREATE FUNCTION sla_due_instances(p_now timestamptz, p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid, next_check_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT i.tenant_id, i.id, i.next_check_at
      FROM sla_instance i
     WHERE i.next_check_at <= p_now
     ORDER BY i.next_check_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION sla_due_instances(timestamptz, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sla_due_instances(timestamptz, int) TO ${app_role};
