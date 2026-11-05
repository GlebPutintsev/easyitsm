-- База знаний (KB-01…05, раздел 5.7 ТЗ): категории, статьи, версии, отзывы читателей.
-- Каждая таблица с tenant_id и RLS по образцу announcements.

CREATE TABLE kb_category (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    description text NOT NULL DEFAULT '',
    sort_order  int  NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL,
    UNIQUE (tenant_id, name)
);
ALTER TABLE kb_category ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kb_category
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Счётчик номеров статей KB-1, KB-2, ... в пределах арендатора.
CREATE TABLE kb_counter (
    tenant_id   uuid PRIMARY KEY REFERENCES tenant (id),
    last_number bigint NOT NULL
);
ALTER TABLE kb_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kb_counter
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Статья. Рабочая копия — версия current_version_no, читатели видят published_version_no.
-- Пока идёт правка опубликованной статьи, читатели продолжают видеть опубликованную версию.
CREATE TABLE article (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant (id),
    number               bigint NOT NULL,
    status               text NOT NULL CHECK (status IN ('draft', 'review', 'published', 'review_due', 'archived')),
    title                text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    category_id          uuid REFERENCES kb_category (id),
    audience             text NOT NULL CHECK (audience IN ('all', 'employees', 'agents', 'group')),
    audience_group_id    uuid,
    owner_id             uuid,
    reviewer_id          uuid,
    review_comment       text,
    current_version_no   int NOT NULL CHECK (current_version_no >= 1),
    published_version_no int,
    published_title      text,
    published_at         timestamptz,
    review_at            timestamptz,
    review_reminded_at   timestamptz,
    source_ticket_id     uuid,
    source_ticket_ref    text,
    -- Поисковый документ опубликованной версии. Обновляется при публикации, очищается при архивации.
    search_vector        tsvector,
    created_at           timestamptz NOT NULL,
    created_by           uuid,
    updated_at           timestamptz NOT NULL,
    updated_by           uuid,
    version              bigint NOT NULL,
    UNIQUE (tenant_id, number),
    CHECK ((audience = 'group') = (audience_group_id IS NOT NULL)),
    CHECK (published_version_no IS NULL OR published_version_no <= current_version_no)
);
CREATE INDEX article_updated_idx ON article (tenant_id, updated_at DESC, id DESC);
CREATE INDEX article_published_idx ON article (tenant_id, published_at DESC, id DESC)
    WHERE published_version_no IS NOT NULL;
CREATE INDEX article_search_idx ON article USING gin (search_vector);
CREATE INDEX article_review_idx ON article (review_at) WHERE status = 'published';
CREATE INDEX article_source_ticket_idx ON article (tenant_id, source_ticket_id) WHERE source_ticket_id IS NOT NULL;
ALTER TABLE article ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Версии статьи (KB-03). Версия неизменна после отправки на проверку или публикации.
CREATE TABLE article_version (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    article_id    uuid NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    version_no    int  NOT NULL CHECK (version_no >= 1),
    title         text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    body          text NOT NULL DEFAULT '',
    notes         text NOT NULL DEFAULT '',
    restored_from int,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL,
    UNIQUE (article_id, version_no)
);
CREATE INDEX article_version_tenant_idx ON article_version (tenant_id);
ALTER TABLE article_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article_version
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Оценка читателя «Помогло / Не помогло», одна на пользователя.
CREATE TABLE article_feedback (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    article_id uuid NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL,
    helpful    boolean NOT NULL,
    comment    text NOT NULL DEFAULT '' CHECK (char_length(comment) <= 2000),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (article_id, user_id)
);
CREATE INDEX article_feedback_tenant_idx ON article_feedback (tenant_id);
ALTER TABLE article_feedback ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article_feedback
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Очередь пересмотра по всем арендаторам для фоновой задачи: kind = 'due' (срок наступил)
-- или 'reminder' (до срока меньше reminder_days дней, напоминание ещё не отправлено).
-- SECURITY DEFINER по образцу platform_purge_idempotency: приложению не нужен обход RLS,
-- функция отдаёт только идентификаторы, а изменение выполняется уже под арендатором.
CREATE FUNCTION knowledge_review_queue(reminder_days int, max_rows int)
    RETURNS TABLE (tenant_id uuid, article_id uuid, kind text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    (SELECT a.tenant_id, a.id, 'due' FROM article a
      WHERE a.status = 'published' AND a.review_at <= now()
      ORDER BY a.review_at LIMIT max_rows)
    UNION ALL
    (SELECT a.tenant_id, a.id, 'reminder' FROM article a
      WHERE a.status = 'published' AND a.review_reminded_at IS NULL
        AND a.review_at > now() AND a.review_at <= now() + make_interval(days => reminder_days)
      ORDER BY a.review_at LIMIT max_rows)
$$;
REVOKE ALL ON FUNCTION knowledge_review_queue(int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION knowledge_review_queue(int, int) TO ${app_role};
