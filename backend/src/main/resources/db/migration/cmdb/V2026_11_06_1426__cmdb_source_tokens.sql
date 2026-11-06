-- Токен источника данных CMDB (CFG-04): агент обнаружения и коннекторы присылают пакеты без входа пользователя,
-- с токеном в Authorization: Bearer. Токен действует только для своего источника; в БД хранится SHA-256,
-- для списка источников — последние символы.
ALTER TABLE ci_source ADD COLUMN token_hash bytea;
ALTER TABLE ci_source ADD COLUMN token_hint text;
ALTER TABLE ci_source ADD COLUMN token_issued_at timestamptz;
CREATE UNIQUE INDEX ci_source_token_uq ON ci_source (token_hash) WHERE token_hash IS NOT NULL;

-- Источник по токену до установки арендатора: пакет приходит без входа. SECURITY DEFINER, наружу — только
-- арендатор и код; дальше источник читается в своём арендаторе под RLS.
CREATE FUNCTION cmdb_source_by_token(p_hash bytea)
    RETURNS TABLE (tenant_id uuid, code text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    SELECT s.tenant_id, s.code FROM ci_source s WHERE s.token_hash = p_hash
$$;
REVOKE ALL ON FUNCTION cmdb_source_by_token(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cmdb_source_by_token(bytea) TO ${app_role};
