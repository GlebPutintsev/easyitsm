-- Модуль identity: пользователи, группы, выдача ролей, учётные данные, MFA, сессии, журнал входов.
-- Все таблицы с данными арендатора под RLS, как в эталонном модуле announcements.
-- Секреты (пароли, токены сессий, резервные коды) хранятся только в виде хешей,
-- секрет TOTP зашифрован ключом приложения (AES-GCM).

-- Пользователи (раздел 7.6 ТЗ). Учётные данные вынесены в user_credential, чтобы изменение
-- профиля не перетирало счётчики неудачных входов и хеш пароля.
CREATE TABLE user_account (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    login           text NOT NULL CHECK (char_length(login) BETWEEN 1 AND 255),
    email           text CHECK (email IS NULL OR char_length(email) <= 320),
    display_name    text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 255),
    locale          text NOT NULL DEFAULT 'ru',
    timezone        text NOT NULL DEFAULT 'Europe/Moscow',
    state           text NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'blocked')),
    -- Кто заблокировал: администратор или источник (LDAP, IdP). Источник может и разблокировать.
    blocked_reason  text CHECK (blocked_reason IN ('admin', 'source')),
    source          text NOT NULL DEFAULT 'local' CHECK (source IN ('local', 'ldap', 'oidc')),
    external_id     text,
    organization_id uuid,
    department_id   uuid,
    manager_id      uuid REFERENCES user_account (id),
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    version         bigint NOT NULL
);
CREATE UNIQUE INDEX user_account_login_uq ON user_account (tenant_id, lower(login));
CREATE UNIQUE INDEX user_account_email_uq ON user_account (tenant_id, lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX user_account_external_uq ON user_account (tenant_id, source, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX user_account_name_idx ON user_account (tenant_id, lower(display_name), id);

-- Учётные данные и факторы. Строка есть у каждого пользователя.
CREATE TABLE user_credential (
    user_id             uuid PRIMARY KEY REFERENCES user_account (id) ON DELETE CASCADE,
    tenant_id           uuid NOT NULL REFERENCES tenant (id),
    password_hash       text,
    password_changed_at timestamptz,
    failed_attempts     int NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    totp_secret         bytea,
    totp_confirmed_at   timestamptz,
    totp_last_step      bigint,
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE recovery_code (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    code_hash  bytea NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX recovery_code_user_idx ON recovery_code (user_id);

-- Ключи WebAuthn и passkeys.
CREATE TABLE webauthn_credential (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant (id),
    user_id         uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    credential_id   bytea NOT NULL,
    attested_data   bytea NOT NULL,
    sign_count      bigint NOT NULL DEFAULT 0,
    uv_initialized  boolean NOT NULL DEFAULT false,
    backup_eligible boolean NOT NULL DEFAULT false,
    backup_state    boolean NOT NULL DEFAULT false,
    name            text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz
);
CREATE UNIQUE INDEX webauthn_credential_uq ON webauthn_credential (tenant_id, credential_id);
CREATE INDEX webauthn_credential_user_idx ON webauthn_credential (user_id);

-- Одноразовые вызовы (challenge) WebAuthn, живут несколько минут.
CREATE TABLE webauthn_ceremony (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid REFERENCES user_account (id) ON DELETE CASCADE,
    purpose    text NOT NULL CHECK (purpose IN ('register', 'authenticate')),
    challenge  bytea NOT NULL,
    expires_at timestamptz NOT NULL
);

-- Группы (зарезервированное слово group не используем).
CREATE TABLE user_group (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    name        text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    description text NOT NULL DEFAULT '',
    type        text NOT NULL DEFAULT 'support' CHECK (type IN ('support', 'security', 'approval', 'other')),
    source      text NOT NULL DEFAULT 'local' CHECK (source IN ('local', 'ldap', 'oidc')),
    external_id text,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    version     bigint NOT NULL
);
CREATE UNIQUE INDEX user_group_name_uq ON user_group (tenant_id, lower(name));
CREATE UNIQUE INDEX user_group_external_uq ON user_group (tenant_id, source, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE group_member (
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    group_id  uuid NOT NULL REFERENCES user_group (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    is_lead   boolean NOT NULL DEFAULT false,
    added_at  timestamptz NOT NULL DEFAULT now(),
    added_by  uuid,
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_member_user_idx ON group_member (user_id);

-- Выдача ролей пользователю или группе (раздел 3 ТЗ). Отзыв не удаляет строку: история нужна аудиту.
CREATE TABLE role_grant (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    role       text NOT NULL CHECK (role IN ('REQ', 'APR', 'AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST',
                                             'ANL', 'ADM', 'SEC', 'AUD', 'SVC', 'AI')),
    user_id    uuid REFERENCES user_account (id) ON DELETE CASCADE,
    group_id   uuid REFERENCES user_group (id) ON DELETE CASCADE,
    reason     text NOT NULL DEFAULT '',
    granted_by uuid,
    granted_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    revoked_by uuid,
    CHECK ((user_id IS NULL) <> (group_id IS NULL))
);
CREATE INDEX role_grant_user_idx ON role_grant (user_id) WHERE revoked_at IS NULL;
CREATE INDEX role_grant_group_idx ON role_grant (group_id) WHERE revoked_at IS NULL;

-- Сессии в куках (SEC-06, SEC-07, SEC-09). В куке случайный токен, в базе — только его SHA-256.
-- state: mfa — пароль проверен, ждём второй фактор; mfa_setup — нужно подключить MFA; active — вход завершён.
CREATE TABLE user_session (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    user_id      uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    token_hash   bytea NOT NULL UNIQUE,
    state        text NOT NULL CHECK (state IN ('mfa', 'mfa_setup', 'active')),
    auth_method  text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    reauth_at    timestamptz,
    revoked_at   timestamptz,
    revoke_reason text,
    ip           text,
    user_agent   text
);
CREATE INDEX user_session_user_idx ON user_session (user_id) WHERE revoked_at IS NULL;

-- «Запомнить устройство» после MFA.
CREATE TABLE trusted_device (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    user_agent text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

-- Сброс пароля по ссылке из письма.
CREATE TABLE password_reset (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

-- Журнал входов для пользователя («Журнал моих входов»). Полный журнал аудита ведёт модуль audit.
CREATE TABLE login_event (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    user_id    uuid REFERENCES user_account (id) ON DELETE CASCADE,
    login      text NOT NULL,
    result     text NOT NULL,
    method     text NOT NULL,
    ip         text,
    user_agent text,
    at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX login_event_user_idx ON login_event (user_id, at DESC);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['user_account', 'user_credential', 'recovery_code', 'webauthn_credential',
                             'webauthn_ceremony', 'user_group', 'group_member', 'role_grant', 'user_session',
                             'trusted_device', 'password_reset', 'login_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

-- Сессия по хешу токена из куки. Арендатор до этого момента неизвестен, поэтому функция работает
-- с правами владельца (SECURITY DEFINER) и отдаёт ровно одну действующую сессию: пользователь и
-- арендатор активны, сессия не отозвана и не истекла. Заодно возвращает действующие роли, чтобы
-- проверка каждого запроса стоила один запрос к базе (ACC-01, ACC-10).
CREATE FUNCTION identity_resolve_session(p_token_hash bytea)
    RETURNS TABLE (session_id uuid, tenant_id uuid, user_id uuid, login text, state text, auth_method text,
                   created_at timestamptz, last_seen_at timestamptz, expires_at timestamptz,
                   reauth_at timestamptz, roles text[])
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT s.id, s.tenant_id, s.user_id, u.login, s.state, s.auth_method, s.created_at, s.last_seen_at,
           s.expires_at, s.reauth_at,
           ARRAY(SELECT DISTINCT g.role FROM role_grant g
                  WHERE g.tenant_id = s.tenant_id AND g.revoked_at IS NULL
                    AND (g.expires_at IS NULL OR g.expires_at > now())
                    AND (g.user_id = s.user_id
                         OR g.group_id IN (SELECT m.group_id FROM group_member m
                                            WHERE m.user_id = s.user_id AND m.tenant_id = s.tenant_id)))
      FROM user_session s
      JOIN user_account u ON u.id = s.user_id AND u.tenant_id = s.tenant_id
      JOIN tenant t ON t.id = s.tenant_id
     WHERE s.token_hash = p_token_hash
       AND s.revoked_at IS NULL
       AND s.expires_at > now()
       AND u.state = 'active'
       AND t.state = 'active'
$$;
REVOKE ALL ON FUNCTION identity_resolve_session(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_resolve_session(bytea) TO ${app_role};

-- Арендатор по коду для входа в облачном режиме (MULTI), когда пользователь ещё неизвестен.
CREATE FUNCTION identity_tenant_by_code(p_code text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT id FROM tenant WHERE code = lower(p_code) AND state = 'active' $$;
REVOKE ALL ON FUNCTION identity_tenant_by_code(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_tenant_by_code(text) TO ${app_role};

-- Очистка истёкших сессий, вызовов WebAuthn и токенов по всем арендаторам (фоновая задача).
CREATE FUNCTION identity_purge_expired() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE n integer := 0; c integer;
BEGIN
    DELETE FROM user_session WHERE expires_at < now() - interval '30 days'
                                OR revoked_at < now() - interval '30 days';
    GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM webauthn_ceremony WHERE expires_at < now();
    GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM trusted_device WHERE expires_at < now();
    GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM password_reset WHERE expires_at < now() - interval '1 day';
    GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    DELETE FROM login_event WHERE at < now() - interval '400 days';
    GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
    RETURN n;
END $$;
REVOKE ALL ON FUNCTION identity_purge_expired() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_expired() TO ${app_role};
