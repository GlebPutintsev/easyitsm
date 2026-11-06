-- Microsoft Teams (CHN-04): бот Azure Bot Service. В отличие от Telegram, MAX и Slack, Bot Framework сам
-- присылает сообщения на адрес сервера, поэтому нужен публичный HTTPS-адрес. Запрос проверяется по JWT Bot
-- Framework; ответ уходит на serviceUrl из проверенного запроса с токеном приложения Microsoft Entra.

ALTER TABLE integration_messenger DROP CONSTRAINT integration_messenger_kind_check;
ALTER TABLE integration_messenger ADD CONSTRAINT integration_messenger_kind_check
    CHECK (kind IN ('telegram', 'max', 'slack', 'teams'));

-- Microsoft App ID бота и арендатор Entra, в котором он зарегистрирован; секрет клиента хранится в token.
ALTER TABLE integration_messenger ADD COLUMN app_id text CHECK (app_id IS NULL OR char_length(app_id) <= 100);
ALTER TABLE integration_messenger ADD COLUMN app_tenant text
    CHECK (app_tenant IS NULL OR char_length(app_tenant) <= 100);
ALTER TABLE integration_messenger ADD CONSTRAINT integration_messenger_teams_check
    CHECK (kind <> 'teams' OR (app_id IS NOT NULL AND app_tenant IS NOT NULL));

-- Идентификаторы разговоров Teams длиннее 100 символов.
ALTER TABLE integration_messenger_link DROP CONSTRAINT integration_messenger_link_chat_id_check;
ALTER TABLE integration_messenger_link ADD CONSTRAINT integration_messenger_link_chat_id_check
    CHECK (char_length(chat_id) <= 500);

-- Куда отвечать в разговор Teams: serviceUrl из последнего проверенного запроса Bot Framework.
CREATE TABLE integration_messenger_conversation (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    messenger_id  uuid NOT NULL REFERENCES integration_messenger (id) ON DELETE CASCADE,
    chat_id       text NOT NULL CHECK (char_length(chat_id) <= 500),
    service_url   text NOT NULL CHECK (char_length(service_url) <= 500),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL
);
CREATE UNIQUE INDEX integration_messenger_conversation_uq ON integration_messenger_conversation (messenger_id, chat_id);

ALTER TABLE integration_messenger_conversation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_messenger_conversation
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Арендатор включённого бота Teams по его id из адреса приёма (запрос приходит без входа).
CREATE FUNCTION integration_teams_bot(p_id uuid)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT m.tenant_id, m.id FROM integration_messenger m WHERE m.id = p_id AND m.kind = 'teams' AND m.enabled
$$;
REVOKE ALL ON FUNCTION integration_teams_bot(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_teams_bot(uuid) TO ${app_role};
