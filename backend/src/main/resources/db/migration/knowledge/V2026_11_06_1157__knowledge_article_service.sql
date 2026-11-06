-- Услуга статьи: над статьёй показывается баннер активного сбоя этой услуги.
-- В MVP услуга — позиция каталога (как ticket.service_id), в 1.0 — услуга CMDB. Без FK: данные другого модуля.
ALTER TABLE article ADD COLUMN service_id uuid;
CREATE INDEX article_service_idx ON article (tenant_id, service_id) WHERE service_id IS NOT NULL;
