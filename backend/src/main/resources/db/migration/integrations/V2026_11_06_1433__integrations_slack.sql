-- Slack (CHN-04): бот подключается через Socket Mode — сервер сам открывает WebSocket к Slack, входящий доступ
-- из интернета не нужен. Для этого кроме токена бота (xoxb-) нужен токен уровня приложения (xapp-).

ALTER TABLE integration_messenger DROP CONSTRAINT integration_messenger_kind_check;
ALTER TABLE integration_messenger ADD CONSTRAINT integration_messenger_kind_check
    CHECK (kind IN ('telegram', 'max', 'slack'));

-- Токен приложения, зашифрованный AES-256-GCM; после сохранения не выводится.
ALTER TABLE integration_messenger ADD COLUMN app_token text;
ALTER TABLE integration_messenger ADD COLUMN app_token_hint text;
ALTER TABLE integration_messenger ADD CONSTRAINT integration_messenger_app_token_check
    CHECK (kind <> 'slack' OR app_token IS NOT NULL);
-- Ссылка, открывающая чат с ботом (профиль бота в рабочем пространстве Slack, t.me/…, max.ru/…).
ALTER TABLE integration_messenger ADD COLUMN bot_url text CHECK (bot_url IS NULL OR char_length(bot_url) <= 500);

-- Боты с постоянным соединением (Slack): фоновый обработчик держит соединение на каждом worker.
CREATE FUNCTION integration_messengers_streaming()
    RETURNS TABLE (tenant_id uuid, id uuid, version bigint)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT m.tenant_id, m.id, m.version FROM integration_messenger m WHERE m.enabled AND m.kind = 'slack'
$$;
REVOKE ALL ON FUNCTION integration_messengers_streaming() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration_messengers_streaming() TO ${app_role};
