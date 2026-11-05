-- Роли и базы для разработки и тестов.
-- easyitsm_owner владеет схемой и выполняет миграции.
-- easyitsm_app — роль приложения: без BYPASSRLS и без права менять схему (ADR-07, раздел 7.1 ТЗ).
CREATE ROLE easyitsm_owner LOGIN PASSWORD 'owner' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE easyitsm_app   LOGIN PASSWORD 'app'   NOSUPERUSER NOBYPASSRLS;

CREATE DATABASE easyitsm      OWNER easyitsm_owner;
CREATE DATABASE easyitsm_test OWNER easyitsm_owner;

\connect easyitsm
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO easyitsm_owner;
GRANT USAGE ON SCHEMA public TO easyitsm_app;

\connect easyitsm_test
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO easyitsm_owner;
GRANT USAGE ON SCHEMA public TO easyitsm_app;
