-- Затронутые услуги объявления: баннер сбоя над статьями базы знаний этих услуг, страница статуса.
-- Пустой список — объявление для всех. Без FK: услуги живут в каталоге (MVP) или CMDB (1.0).
ALTER TABLE announcement ADD COLUMN IF NOT EXISTS service_ids uuid[] NOT NULL DEFAULT '{}';
