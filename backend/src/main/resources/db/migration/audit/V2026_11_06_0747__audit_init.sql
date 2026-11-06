-- Журнал аудита (SEC-29…SEC-32, раздел 7.6 ТЗ, угроза T-18 «подмена журнала»).
--
-- Записи только добавляются. Порядок и цепочку хешей задаёт база, а не приложение: триггер при вставке
-- берёт голову цепочки арендатора под блокировкой, присваивает номер, время и считает
-- hash = sha256(канонический JSON записи вместе с prev_hash). Приложение не может ни изменить, ни удалить
-- запись: у роли ${app_role} нет UPDATE, DELETE и TRUNCATE, а триггеры запрещают изменение и владельцу,
-- пока он явно не отключит их (это видно в журнале PostgreSQL). Подмену, удаление из середины и обрезку хвоста
-- находит проверка целостности (AuditChainVerifier), копия записей уходит в SIEM.
--
-- Это журнал, а не изменяемая сущность: служебных полей updated_* и version нет, created_at — это at,
-- created_by — actor_id.

CREATE TABLE audit_log (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    seq         bigint NOT NULL,
    at          timestamptz NOT NULL,
    category    text NOT NULL CHECK (category IN ('auth', 'access', 'config', 'data', 'security', 'audit')),
    action      text NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.]{1,79}$'),
    result      text NOT NULL CHECK (result IN ('success', 'failure', 'denied')),
    actor_id    uuid,
    actor_login text CHECK (char_length(actor_login) <= 256),
    object_type text CHECK (char_length(object_type) <= 200),
    object_id   text CHECK (char_length(object_id) <= 200),
    ip          text CHECK (char_length(ip) <= 64),
    user_agent  text CHECK (char_length(user_agent) <= 512),
    trace_id    text CHECK (char_length(trace_id) <= 64),
    details     jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    prev_hash   text NOT NULL,
    hash        text NOT NULL,
    UNIQUE (tenant_id, seq)
);
CREATE INDEX audit_log_at_idx ON audit_log (tenant_id, at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (tenant_id, actor_id, seq DESC);
CREATE INDEX audit_log_action_idx ON audit_log (tenant_id, action, seq DESC);
CREATE INDEX audit_log_object_idx ON audit_log (tenant_id, object_type, object_id);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM ${app_role};

-- Голова цепочки арендатора: номер и хеш последней записи. Пишет только триггер (SECURITY DEFINER),
-- приложение читает свою строку при проверке целостности: так находится обрезанный хвост.
CREATE TABLE audit_chain_head (
    tenant_id  uuid PRIMARY KEY REFERENCES tenant (id),
    seq        bigint NOT NULL,
    hash       text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE audit_chain_head ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_chain_head USING (tenant_id = app_current_tenant());
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON audit_chain_head FROM ${app_role};

-- Канонический вид записи для хеша: jsonb хранит ключи в упорядоченном виде, поэтому текст однозначен.
-- Время — UTC с микросекундами, не зависит от настроек сессии.
CREATE FUNCTION audit_entry_hash(e audit_log) RETURNS text
    LANGUAGE sql STABLE
AS $$
    SELECT encode(sha256(convert_to(jsonb_build_object(
        'prev', e.prev_hash,
        'id', e.id,
        'tenant', e.tenant_id,
        'seq', e.seq,
        'at', to_char(e.at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'category', e.category,
        'action', e.action,
        'result', e.result,
        'actor', e.actor_id,
        'login', e.actor_login,
        'object_type', e.object_type,
        'object_id', e.object_id,
        'ip', e.ip,
        'ua', e.user_agent,
        'trace', e.trace_id,
        'details', e.details)::text, 'UTF8')), 'hex')
$$;
GRANT EXECUTE ON FUNCTION audit_entry_hash(audit_log) TO ${app_role};

CREATE FUNCTION audit_log_chain() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
    head record;
BEGIN
    INSERT INTO audit_chain_head (tenant_id, seq, hash)
    VALUES (NEW.tenant_id, 0, repeat('0', 64))
    ON CONFLICT (tenant_id) DO NOTHING;
    SELECT seq, hash INTO head FROM audit_chain_head WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    NEW.seq := head.seq + 1;
    NEW.prev_hash := head.hash;
    NEW.at := clock_timestamp();
    NEW.hash := audit_entry_hash(NEW);
    UPDATE audit_chain_head SET seq = NEW.seq, hash = NEW.hash, updated_at = NEW.at
     WHERE tenant_id = NEW.tenant_id;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION audit_log_chain() FROM PUBLIC;
CREATE TRIGGER audit_log_chain BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_chain();

CREATE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_log: записи журнала аудита нельзя изменять или удалять'
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();

-- Доставка в SIEM (SEC-32): до какого номера записи арендатора доставлены, и состояние повторов.
CREATE TABLE audit_siem_state (
    tenant_id       uuid PRIMARY KEY REFERENCES tenant (id),
    delivered_seq   bigint NOT NULL DEFAULT 0,
    delivered_at    timestamptz,
    failures        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text,
    last_error_at   timestamptz
);
ALTER TABLE audit_siem_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_siem_state
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());

-- Арендаторы, у которых есть недоставленные записи и наступило время попытки. Для фоновой задачи по всем
-- арендаторам: дальше она работает под каждым арендатором отдельно (TenantContext.runAs).
CREATE FUNCTION audit_siem_pending(max_tenants int) RETURNS TABLE (tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT h.tenant_id
      FROM audit_chain_head h
      LEFT JOIN audit_siem_state s ON s.tenant_id = h.tenant_id
     WHERE h.seq > coalesce(s.delivered_seq, 0)
       AND (s.next_attempt_at IS NULL OR s.next_attempt_at <= now())
     ORDER BY h.tenant_id
     LIMIT max_tenants
$$;
REVOKE ALL ON FUNCTION audit_siem_pending(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_siem_pending(int) TO ${app_role};

-- Арендаторы с журналом: для ночной проверки целостности по всем арендаторам.
CREATE FUNCTION audit_tenants() RETURNS TABLE (tenant_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT tenant_id FROM audit_chain_head ORDER BY tenant_id
$$;
REVOKE ALL ON FUNCTION audit_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_tenants() TO ${app_role};
