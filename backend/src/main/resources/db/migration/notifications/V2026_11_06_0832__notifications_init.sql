-- Уведомления (NTF-01, NTF-02, NTF-05): центр уведомлений в интерфейсе, шаблоны арендатора поверх
-- встроенных, подписки Web Push и ключи VAPID. Письма уходят через очередь модуля mail.

CREATE TABLE notification (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL,
    kind       text NOT NULL CHECK (char_length(kind) BETWEEN 1 AND 100),
    title      text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 500),
    body       text NOT NULL DEFAULT '' CHECK (char_length(body) <= 2000),
    link       text CHECK (link IS NULL OR char_length(link) <= 1000),
    ticket_id  uuid,
    read_at    timestamptz,
    push_state text NOT NULL DEFAULT 'none' CHECK (push_state IN ('none', 'pending', 'sent', 'failed')),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);
CREATE INDEX notification_user_idx ON notification (tenant_id, user_id, id DESC);
CREATE INDEX notification_unread_idx ON notification (tenant_id, user_id) WHERE read_at IS NULL;
CREATE INDEX notification_push_idx ON notification (created_at) WHERE push_state = 'pending';
ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Шаблон арендатора заменяет встроенный шаблон того же вида и языка. Удаление возвращает встроенный.
CREATE TABLE notification_template (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    kind       text NOT NULL CHECK (char_length(kind) BETWEEN 1 AND 100),
    locale     text NOT NULL CHECK (locale IN ('ru', 'en')),
    subject    text NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 500),
    body       text NOT NULL CHECK (char_length(body) <= 20000),
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL,
    UNIQUE (tenant_id, kind, locale)
);
ALTER TABLE notification_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_template
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Подписки Web Push браузеров пользователя (RFC 8030, ключи RFC 8291).
CREATE TABLE push_subscription (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL,
    endpoint   text NOT NULL CHECK (char_length(endpoint) BETWEEN 10 AND 2000),
    p256dh     text NOT NULL CHECK (char_length(p256dh) <= 200),
    auth       text NOT NULL CHECK (char_length(auth) <= 100),
    user_agent text,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);
CREATE UNIQUE INDEX push_subscription_endpoint_uq ON push_subscription (tenant_id, endpoint);
CREATE INDEX push_subscription_user_idx ON push_subscription (tenant_id, user_id);
ALTER TABLE push_subscription ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON push_subscription
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Ключи VAPID (RFC 8292) арендатора: создаются при первом обращении, закрытый ключ зашифрован.
CREATE TABLE push_vapid_key (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL UNIQUE REFERENCES tenant (id),
    public_key      text NOT NULL,
    private_key_enc text NOT NULL,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
ALTER TABLE push_vapid_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON push_vapid_key
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

CREATE FUNCTION notification_push_due(p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT n.tenant_id, n.id
      FROM notification n
     WHERE n.push_state = 'pending'
     ORDER BY n.created_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION notification_push_due(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_push_due(int) TO ${app_role};
