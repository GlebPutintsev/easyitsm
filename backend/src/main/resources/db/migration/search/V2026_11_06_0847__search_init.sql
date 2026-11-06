-- Глобальный поиск (TCK-15, NFR-04). Основной движок — OpenSearch, его индекс воссоздаётся из БД (12.4 ТЗ).
-- Здесь только служебные таблицы модуля: очередь индексации и состояние индекса арендатора.

-- Очередь индексации: что поменялось и ждёт отправки в OpenSearch. Одна строка на запись, повторное
-- изменение только сдвигает seq. Строка удаляется после успешной отправки той версии, которую забрал обработчик.
CREATE SEQUENCE search_index_queue_seq;
CREATE TABLE search_index_queue (
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    kind            text NOT NULL CHECK (kind IN ('ticket', 'article')),
    entity_id       uuid NOT NULL,
    seq             bigint NOT NULL DEFAULT nextval('search_index_queue_seq'),
    enqueued_at     timestamptz NOT NULL DEFAULT now(),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    leased_until    timestamptz,
    last_error      text,
    PRIMARY KEY (tenant_id, kind, entity_id)
);
CREATE INDEX search_index_queue_due_idx ON search_index_queue (next_attempt_at);

ALTER TABLE search_index_queue ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_index_queue
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Состояние индекса арендатора. ready_at — индекс полностью построен, поиск идёт в OpenSearch;
-- пока null (первая загрузка или пересоздание), выдача идёт из резервного поиска PostgreSQL.
CREATE TABLE search_index_state (
    tenant_id          uuid PRIMARY KEY REFERENCES tenant (id),
    index_name         text NOT NULL,
    full_requested_at  timestamptz NOT NULL,
    ready_at           timestamptz,
    last_indexed_at    timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE search_index_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON search_index_state
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Обработчик забирает пачку из очереди по всем арендаторам с арендой: несколько подов worker не берут
-- одну строку дважды, а упавший под отпускает её по истечении аренды.
CREATE FUNCTION search_claim_batch(p_limit int, p_lease_seconds int)
    RETURNS TABLE (tenant_id uuid, kind text, entity_id uuid, seq bigint, attempts int)
    LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public
AS $$
    UPDATE search_index_queue q
       SET leased_until = now() + make_interval(secs => p_lease_seconds), attempts = q.attempts + 1
      FROM (SELECT s.tenant_id, s.kind, s.entity_id
              FROM search_index_queue s
             WHERE s.next_attempt_at <= now() AND (s.leased_until IS NULL OR s.leased_until < now())
             ORDER BY s.next_attempt_at
             LIMIT p_limit
               FOR UPDATE SKIP LOCKED) c
     WHERE q.tenant_id = c.tenant_id AND q.kind = c.kind AND q.entity_id = c.entity_id
    RETURNING q.tenant_id, q.kind, q.entity_id, q.seq, q.attempts
$$;
REVOKE ALL ON FUNCTION search_claim_batch(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_claim_batch(int, int) TO ${app_role};

-- Арендаторы для первичной загрузки индекса и ночной сверки.
CREATE FUNCTION search_tenants()
    RETURNS TABLE (tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT t.id FROM tenant t WHERE t.state <> 'terminated' ORDER BY t.id
$$;
REVOKE ALL ON FUNCTION search_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION search_tenants() TO ${app_role};
