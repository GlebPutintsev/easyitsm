-- Мессенджеры (CHN-04, CHN-06, NTF-01): бот арендатора в Telegram или MAX, привязка пользователей, заявки из
-- сообщений и уведомления в бот. Бот опрашивается фоновым обработчиком: входящего доступа из интернета не нужно.

CREATE TABLE integration_messenger (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenant (id),
    kind           text NOT NULL CHECK (kind IN ('telegram', 'max')),
    name           text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    -- Токен бота, зашифрованный AES-256-GCM; после сохранения не выводится.
    token          text NOT NULL,
    token_hint     text NOT NULL,
    -- Адрес API; null — официальный. Свой адрес (локальный сервер Bot API, прокси) должен быть разрешён.
    base_url       text CHECK (base_url IS NULL OR char_length(base_url) <= 500),
    bot_username   text,
    enabled        boolean NOT NULL DEFAULT true,
    -- Создавать заявки из сообщений; тип и группа новых заявок.
    allow_tickets  boolean NOT NULL DEFAULT true,
    ticket_type    text NOT NULL DEFAULT 'incident' CHECK (ticket_type IN ('incident', 'request')),
    group_id       uuid,
    -- Что можно передавать в мессенджер (CHN-06): title — название заявки, text — текст уведомлений и ответов,
    -- link — ссылка в систему. Номер заявки передаётся всегда.
    data_fields    text[] NOT NULL DEFAULT '{title,link}',
    -- Позиция опроса: offset Telegram или marker MAX.
    poll_cursor    text,
    last_poll_at   timestamptz,
    last_error     text,
    last_event_at  timestamptz,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint NOT NULL
);
CREATE INDEX integration_messenger_tenant_idx ON integration_messenger (tenant_id, name);

ALTER TABLE integration_messenger ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Привязка пользователя к чату с ботом: одна на пользователя и бот, один чат — один пользователь.
CREATE TABLE integration_messenger_link (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    messenger_id  uuid NOT NULL REFERENCES integration_messenger (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL,
    chat_id       text NOT NULL CHECK (char_length(chat_id) <= 100),
    external_name text,
    linked_at     timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE UNIQUE INDEX integration_messenger_link_user_uq ON integration_messenger_link (messenger_id, user_id);
CREATE UNIQUE INDEX integration_messenger_link_chat_uq ON integration_messenger_link (messenger_id, chat_id);
CREATE INDEX integration_messenger_link_by_user_idx ON integration_messenger_link (tenant_id, user_id);

ALTER TABLE integration_messenger_link ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger_link
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Одноразовый код привязки из профиля: в БД только SHA-256, живёт 15 минут.
CREATE TABLE integration_messenger_code (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    messenger_id  uuid NOT NULL REFERENCES integration_messenger (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL,
    code_hash     bytea NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE UNIQUE INDEX integration_messenger_code_uq ON integration_messenger_code (messenger_id, code_hash);

ALTER TABLE integration_messenger_code ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger_code
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Очередь сообщений в мессенджер: уведомления и ответы бота.
CREATE TABLE integration_messenger_outbox (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    messenger_id    uuid NOT NULL REFERENCES integration_messenger (id) ON DELETE CASCADE,
    chat_id         text NOT NULL,
    text            text NOT NULL CHECK (char_length(text) <= 4000),
    reply_to        text,
    ticket_id       uuid,
    ticket_number   text,
    status          text NOT NULL CHECK (status IN ('pending', 'sent', 'dead')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
CREATE INDEX integration_messenger_outbox_due_idx ON integration_messenger_outbox (next_attempt_at)
    WHERE status = 'pending';

ALTER TABLE integration_messenger_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger_outbox
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Какое сообщение чата относится к какой заявке: ответ на него становится комментарием.
CREATE TABLE integration_messenger_thread (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    messenger_id  uuid NOT NULL REFERENCES integration_messenger (id) ON DELETE CASCADE,
    chat_id       text NOT NULL,
    message_id    text NOT NULL,
    ticket_id     uuid NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL
);
CREATE UNIQUE INDEX integration_messenger_thread_uq ON integration_messenger_thread (messenger_id, chat_id, message_id);

ALTER TABLE integration_messenger_thread ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger_thread
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Включённые боты всех арендаторов для фонового опроса: наружу только идентификаторы.
CREATE FUNCTION integration_messengers_enabled()
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT m.tenant_id, m.id FROM integration_messenger m WHERE m.enabled
$$;
REVOKE ALL ON FUNCTION integration_messengers_enabled() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_messengers_enabled() TO ${app_role};

-- Сообщения в мессенджер с наступившим сроком во всех арендаторах.
CREATE FUNCTION integration_messenger_due(p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT o.tenant_id, o.id FROM integration_messenger_outbox o
     WHERE o.status = 'pending' AND o.next_attempt_at <= now()
     ORDER BY o.next_attempt_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION integration_messenger_due(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_messenger_due(int) TO ${app_role};

-- Отправленные и неудачные сообщения, истёкшие коды и старые связи сообщений с заявками удаляются вместе
-- с журналом мониторинга.
CREATE OR REPLACE FUNCTION integration_purge_events(keep_days integer) RETURNS integer
    LANGUAGE sql SECURITY DEFINER SET search_path = public
AS $$
    WITH events AS (
        DELETE FROM integration_event_log
         WHERE received_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1),
    deliveries AS (
        DELETE FROM integration_webhook_delivery
         WHERE status <> 'pending' AND created_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1),
    outbox AS (
        DELETE FROM integration_messenger_outbox
         WHERE status <> 'pending' AND created_at < now() - make_interval(days => greatest(keep_days, 1))
        RETURNING 1),
    codes AS (
        DELETE FROM integration_messenger_code WHERE expires_at < now() RETURNING 1),
    threads AS (
        DELETE FROM integration_messenger_thread
         WHERE created_at < now() - make_interval(days => greatest(keep_days, 1) * 6)
        RETURNING 1)
    SELECT ((SELECT count(*) FROM events) + (SELECT count(*) FROM deliveries) + (SELECT count(*) FROM outbox)
            + (SELECT count(*) FROM codes) + (SELECT count(*) FROM threads))::int
$$;
