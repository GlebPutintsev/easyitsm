-- Объявления портала (PRT-01): плановые работы и сбои.
-- Это эталонный модуль: так оформляется любая таблица с данными арендатора.
CREATE TABLE announcement (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    kind       text NOT NULL CHECK (kind IN ('info', 'maintenance', 'outage')),
    title      text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    body       text NOT NULL DEFAULT '',
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz,
    published  boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX announcement_active_idx ON announcement (tenant_id, published, starts_at DESC, id DESC);

ALTER TABLE announcement ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON announcement
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
