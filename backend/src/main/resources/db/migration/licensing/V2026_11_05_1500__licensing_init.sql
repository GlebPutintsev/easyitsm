-- Лицензия инсталляции (ADM-07). Файлы, загруженные администратором; действует последний.
-- Исключение из правила tenant_id: лицензия принадлежит инсталляции, а не арендатору,
-- и не содержит данных заказчика, кроме названия владельца лицензии.
CREATE TABLE license_file (
    id           uuid PRIMARY KEY,
    license_id   text NOT NULL,
    content      text NOT NULL CHECK (octet_length(content) <= 65536),
    installed_at timestamptz NOT NULL,
    installed_by uuid
);
CREATE INDEX license_file_installed_idx ON license_file (installed_at DESC, id DESC);
-- История загрузок неизменна: приложение только добавляет записи.
REVOKE UPDATE, DELETE ON license_file FROM ${app_role};
