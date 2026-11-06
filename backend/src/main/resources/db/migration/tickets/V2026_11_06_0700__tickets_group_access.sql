-- Видимость заявок по группам (ACC-02, ACC-04): фильтр списков проверяет группу заявки, группу
-- безопасности конфиденциальной заявки и того, кто её зарегистрировал (участник наравне с заявителем).

-- Очередь группы без фильтра по статусу, сортировка по умолчанию — новые сверху.
CREATE INDEX ticket_group_created_idx ON ticket (tenant_id, group_id, created_at DESC, id DESC);

-- Заявки, зарегистрированные агентом за другого человека.
CREATE INDEX ticket_created_by_idx ON ticket (tenant_id, created_by);

-- Конфиденциальные заявки видят члены группы безопасности.
CREATE INDEX ticket_security_group_idx ON ticket (tenant_id, security_group_id) WHERE confidential;
