-- Канал почты (CHN-01, CHN-02, поток 6.4 ТЗ): исходящая почта с повторами, ящики входящей почты,
-- журнал входящих писем с результатами SPF/DKIM/DMARC и решением, что с письмом сделано.
-- Пароли и секреты клиентов хранятся только зашифрованными (AES-GCM, ключ easyitsm.mail.secret-key).

-- Отправка: SMTP-сервер заказчика или Microsoft Graph. Одна настройка на арендатора.
CREATE TABLE mail_transport (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL UNIQUE REFERENCES tenant (id),
    protocol        text NOT NULL CHECK (protocol IN ('smtp', 'graph')),
    host            text CHECK (host IS NULL OR char_length(host) <= 255),
    port            int CHECK (port IS NULL OR port BETWEEN 1 AND 65535),
    security        text NOT NULL DEFAULT 'starttls' CHECK (security IN ('none', 'starttls', 'ssl')),
    username        text CHECK (username IS NULL OR char_length(username) <= 320),
    secret_enc      text,
    graph_tenant    text CHECK (graph_tenant IS NULL OR char_length(graph_tenant) <= 255),
    graph_client_id text CHECK (graph_client_id IS NULL OR char_length(graph_client_id) <= 255),
    from_address    text NOT NULL CHECK (char_length(from_address) BETWEEN 3 AND 320),
    from_name       text NOT NULL DEFAULT '' CHECK (char_length(from_name) <= 255),
    reply_to        text CHECK (reply_to IS NULL OR char_length(reply_to) <= 320),
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
ALTER TABLE mail_transport ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mail_transport
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Ящики входящей почты: IMAP, Microsoft Graph или входящий SMTP (письма передаёт почтовый сервер заказчика).
CREATE TABLE mail_mailbox (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    address         text NOT NULL CHECK (char_length(address) BETWEEN 3 AND 320),
    protocol        text NOT NULL CHECK (protocol IN ('imap', 'graph', 'smtp')),
    host            text CHECK (host IS NULL OR char_length(host) <= 255),
    port            int CHECK (port IS NULL OR port BETWEEN 1 AND 65535),
    security        text NOT NULL DEFAULT 'ssl' CHECK (security IN ('none', 'starttls', 'ssl')),
    username        text CHECK (username IS NULL OR char_length(username) <= 320),
    secret_enc      text,
    folder          text NOT NULL DEFAULT 'INBOX' CHECK (char_length(folder) BETWEEN 1 AND 255),
    graph_tenant    text CHECK (graph_tenant IS NULL OR char_length(graph_tenant) <= 255),
    graph_client_id text CHECK (graph_client_id IS NULL OR char_length(graph_client_id) <= 255),
    ticket_type     text NOT NULL DEFAULT 'incident' CHECK (ticket_type IN ('incident', 'request')),
    group_id        uuid,
    -- enforce: по политике DMARC домена; strict: любой провал DMARC отклоняется; monitor: только запись в журнал
    auth_policy     text NOT NULL DEFAULT 'enforce' CHECK (auth_policy IN ('enforce', 'strict', 'monitor')),
    -- authserv-id почтовых серверов, чьему заголовку Authentication-Results доверяем (через запятую)
    trusted_authserv text NOT NULL DEFAULT '' CHECK (char_length(trusted_authserv) <= 1000),
    enabled         boolean NOT NULL DEFAULT true,
    last_polled_at  timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
CREATE UNIQUE INDEX mail_mailbox_address_uq ON mail_mailbox (tenant_id, lower(address));
ALTER TABLE mail_mailbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mail_mailbox
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Журнал входящих писем. Исходное письмо (raw) хранится только у писем в карантине, чтобы его можно было принять.
CREATE TABLE mail_inbound (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    mailbox_id   uuid REFERENCES mail_mailbox (id) ON DELETE SET NULL,
    message_id   text CHECK (message_id IS NULL OR char_length(message_id) <= 998),
    from_address text CHECK (from_address IS NULL OR char_length(from_address) <= 320),
    from_name    text,
    subject      text,
    received_at  timestamptz NOT NULL,
    spf          text NOT NULL DEFAULT 'none',
    dkim         text NOT NULL DEFAULT 'none',
    dmarc        text NOT NULL DEFAULT 'none',
    verdict      text NOT NULL CHECK (verdict IN ('ticket_created', 'comment_added', 'auto_reply', 'loop',
                     'rate_limited', 'auth_failed', 'quarantined', 'unknown_sender', 'released', 'error')),
    detail       text,
    ticket_id    uuid,
    raw          bytea,
    raw_size     int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL
);
-- Повторно забранное письмо (сбой отметки «прочитано», повтор доставки SMTP) не создаёт вторую заявку.
CREATE UNIQUE INDEX mail_inbound_message_uq ON mail_inbound (tenant_id, message_id) WHERE message_id IS NOT NULL;
CREATE INDEX mail_inbound_sender_idx ON mail_inbound (tenant_id, lower(from_address), received_at);
CREATE INDEX mail_inbound_list_idx ON mail_inbound (tenant_id, id DESC);
ALTER TABLE mail_inbound ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mail_inbound
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Очередь исходящих писем: отправка в роли worker с повторами. Тело письма стирается после отправки.
CREATE TABLE mail_outgoing (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenant (id),
    kind              text NOT NULL CHECK (char_length(kind) BETWEEN 1 AND 100),
    recipient         text NOT NULL CHECK (char_length(recipient) BETWEEN 3 AND 320),
    recipient_name    text,
    recipient_user_id uuid,
    ticket_id         uuid,
    subject           text NOT NULL,
    body_text         text NOT NULL DEFAULT '',
    body_html         text,
    message_id        text NOT NULL,
    status            text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed', 'suppressed')),
    attempts          int NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz,
    last_error        text,
    sent_at           timestamptz,
    created_at        timestamptz NOT NULL,
    created_by        uuid,
    updated_at        timestamptz NOT NULL,
    updated_by        uuid,
    version           bigint NOT NULL
);
CREATE INDEX mail_outgoing_due_idx ON mail_outgoing (next_attempt_at) WHERE status = 'pending';
CREATE INDEX mail_outgoing_list_idx ON mail_outgoing (tenant_id, id DESC);
ALTER TABLE mail_outgoing ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mail_outgoing
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Фоновые задачи по всем арендаторам (образец sla_due_instances): функция отдаёт только (tenant_id, id),
-- дальше запись читается и меняется под RLS в арендаторе через TenantContext.runAs.
CREATE FUNCTION mail_outgoing_due(p_now timestamptz, p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT o.tenant_id, o.id
      FROM mail_outgoing o
     WHERE o.status = 'pending' AND o.next_attempt_at <= p_now
     ORDER BY o.next_attempt_at
     LIMIT p_limit
$$;
REVOKE ALL ON FUNCTION mail_outgoing_due(timestamptz, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mail_outgoing_due(timestamptz, int) TO ${app_role};

CREATE FUNCTION mail_mailboxes_due(p_before timestamptz)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT m.tenant_id, m.id
      FROM mail_mailbox m
     WHERE m.enabled AND m.protocol IN ('imap', 'graph')
       AND (m.last_polled_at IS NULL OR m.last_polled_at <= p_before)
     ORDER BY m.last_polled_at NULLS FIRST
$$;
REVOKE ALL ON FUNCTION mail_mailboxes_due(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mail_mailboxes_due(timestamptz) TO ${app_role};

-- Входящий SMTP общий для установки: арендатор определяется по адресу получателя.
CREATE FUNCTION mail_mailbox_by_address(p_address text)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT m.tenant_id, m.id
      FROM mail_mailbox m
     WHERE m.enabled AND m.protocol = 'smtp' AND lower(m.address) = lower(p_address)
     ORDER BY m.created_at
     LIMIT 1
$$;
REVOKE ALL ON FUNCTION mail_mailbox_by_address(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION mail_mailbox_by_address(text) TO ${app_role};
