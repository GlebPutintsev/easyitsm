-- ИТ-активы (раздел 4.10 ТЗ): оборудование (AST-01), ПО и лицензии (AST-03), договоры и поставщики (AST-04),
-- связь актива с КЕ, сотрудником и заявками (AST-05). Модуль владеет только своими таблицами: сотрудник, КЕ
-- и заявка хранятся как идентификаторы, имена берутся через api модулей identity, cmdb и tickets.

-- Местоположения: офис, этаж, склад. Справочник модуля, на него ссылаются активы.
CREATE TABLE asset_location (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    address    text,
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint NOT NULL
);
CREATE UNIQUE INDEX asset_location_name_uq ON asset_location (tenant_id, lower(name));

-- Поставщики (AST-04).
CREATE TABLE asset_vendor (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenant (id),
    name         text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    tax_id       text CHECK (tax_id IS NULL OR char_length(tax_id) <= 20),
    contact_name text,
    email        text,
    phone        text,
    website      text,
    notes        text NOT NULL DEFAULT '',
    active       boolean NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    version      bigint NOT NULL
);
CREATE UNIQUE INDEX asset_vendor_name_uq ON asset_vendor (tenant_id, lower(name));

-- Договоры (AST-04). Напоминание о продлении уходит ответственному за renewal_notice_days до окончания;
-- reminded_for — дата окончания, о которой уже напомнили: после продления (новая дата) напоминание придёт снова.
CREATE TABLE asset_contract (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant (id),
    number              text NOT NULL CHECK (char_length(number) BETWEEN 1 AND 100),
    title               text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 255),
    kind                text NOT NULL CHECK (kind IN ('purchase', 'lease', 'support', 'license', 'service', 'other')),
    vendor_id           uuid REFERENCES asset_vendor (id),
    starts_on           date NOT NULL,
    ends_on             date,
    renewal_notice_days int NOT NULL DEFAULT 30 CHECK (renewal_notice_days BETWEEN 0 AND 365),
    auto_renew          boolean NOT NULL DEFAULT false,
    amount              numeric(14, 2) CHECK (amount IS NULL OR amount >= 0),
    currency            text NOT NULL DEFAULT 'RUB' CHECK (currency ~ '^[A-Z]{3}$'),
    owner_id            uuid,
    notes               text NOT NULL DEFAULT '',
    terminated          boolean NOT NULL DEFAULT false,
    reminded_for        date,
    created_at          timestamptz NOT NULL,
    created_by          uuid,
    updated_at          timestamptz NOT NULL,
    updated_by          uuid,
    version             bigint NOT NULL,
    CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE UNIQUE INDEX asset_contract_number_uq ON asset_contract (tenant_id, lower(number));
CREATE INDEX asset_contract_ends_idx ON asset_contract (tenant_id, ends_on) WHERE NOT terminated;
CREATE INDEX asset_contract_vendor_idx ON asset_contract (tenant_id, vendor_id);

-- Оборудование (AST-01). Инвентарный номер уникален в арендаторе. Состояния: на складе, выдан, в ремонте, списан.
-- Сотрудник (holder_id) обязателен у выданного, может остаться у отданного в ремонт и отсутствует на складе
-- и у списанного. Причина есть только у списанного.
CREATE TABLE asset (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant (id),
    tag                 text NOT NULL CHECK (char_length(tag) BETWEEN 1 AND 64),
    name                text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 255),
    kind                text NOT NULL CHECK (kind IN ('laptop', 'desktop', 'monitor', 'server', 'network', 'phone',
                                                      'tablet', 'printer', 'peripheral', 'other')),
    manufacturer        text,
    model               text,
    serial              text,
    state               text NOT NULL DEFAULT 'in_stock' CHECK (state IN ('in_stock', 'in_use', 'in_repair', 'retired')),
    holder_id           uuid,
    location_id         uuid REFERENCES asset_location (id),
    ci_id               uuid,
    vendor_id           uuid REFERENCES asset_vendor (id),
    contract_id         uuid REFERENCES asset_contract (id),
    purchased_on        date,
    warranty_to         date,
    service_life_months int CHECK (service_life_months IS NULL OR service_life_months BETWEEN 1 AND 600),
    cost                numeric(14, 2) CHECK (cost IS NULL OR cost >= 0),
    currency            text NOT NULL DEFAULT 'RUB' CHECK (currency ~ '^[A-Z]{3}$'),
    notes               text NOT NULL DEFAULT '',
    retire_reason       text,
    retired_at          timestamptz,
    created_at          timestamptz NOT NULL,
    created_by          uuid,
    updated_at          timestamptz NOT NULL,
    updated_by          uuid,
    version             bigint NOT NULL,
    CHECK (state <> 'in_use' OR holder_id IS NOT NULL),
    CHECK (state NOT IN ('in_stock', 'retired') OR holder_id IS NULL),
    CHECK ((state = 'retired') = (retire_reason IS NOT NULL))
);
CREATE UNIQUE INDEX asset_tag_uq ON asset (tenant_id, lower(tag));
CREATE INDEX asset_list_idx ON asset (tenant_id, id DESC);
CREATE INDEX asset_holder_idx ON asset (tenant_id, holder_id) WHERE holder_id IS NOT NULL;
CREATE INDEX asset_ci_idx ON asset (tenant_id, ci_id) WHERE ci_id IS NOT NULL;
CREATE INDEX asset_contract_idx ON asset (tenant_id, contract_id) WHERE contract_id IS NOT NULL;
CREATE INDEX asset_serial_idx ON asset (tenant_id, lower(serial)) WHERE serial IS NOT NULL;

-- История актива: создание, выдача, возврат, передача, ремонт, списание, изменение полей. Только дописывается.
CREATE TABLE asset_event (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    asset_id    uuid NOT NULL REFERENCES asset (id),
    kind        text NOT NULL CHECK (kind IN ('created', 'updated', 'issued', 'returned', 'transferred', 'repair',
                                              'retired', 'ticket_linked', 'ticket_unlinked')),
    from_state  text,
    to_state    text,
    from_holder uuid,
    to_holder   uuid,
    ticket_id   uuid,
    comment     text,
    actor_id    uuid,
    at          timestamptz NOT NULL
);
CREATE INDEX asset_event_asset_idx ON asset_event (tenant_id, asset_id, at DESC, id DESC);
REVOKE UPDATE, DELETE ON asset_event FROM ${app_role};

-- Связь актива с заявками (AST-05): заявка «сломался ноутбук» ↔ ноутбук.
CREATE TABLE asset_ticket (
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    asset_id  uuid NOT NULL REFERENCES asset (id),
    ticket_id uuid NOT NULL,
    linked_by uuid,
    linked_at timestamptz NOT NULL,
    PRIMARY KEY (asset_id, ticket_id)
);
CREATE INDEX asset_ticket_ticket_idx ON asset_ticket (tenant_id, ticket_id);

-- Лицензии ПО (AST-03). Метрика: на устройство, на пользователя, конкурентная, на организацию, подписка.
-- quantity NULL — без ограничения (на организацию). Ключи лицензий не храним: это секрет (как CFG-08 для КЕ).
CREATE TABLE software_license (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenant (id),
    product             text NOT NULL CHECK (char_length(product) BETWEEN 1 AND 255),
    publisher           text,
    metric              text NOT NULL CHECK (metric IN ('device', 'user', 'concurrent', 'site', 'subscription')),
    quantity            int CHECK (quantity IS NULL OR quantity >= 0),
    valid_from          date,
    valid_to            date,
    renewal_notice_days int NOT NULL DEFAULT 30 CHECK (renewal_notice_days BETWEEN 0 AND 365),
    vendor_id           uuid REFERENCES asset_vendor (id),
    contract_id         uuid REFERENCES asset_contract (id),
    cost                numeric(14, 2) CHECK (cost IS NULL OR cost >= 0),
    currency            text NOT NULL DEFAULT 'RUB' CHECK (currency ~ '^[A-Z]{3}$'),
    owner_id            uuid,
    notes               text NOT NULL DEFAULT '',
    reminded_for        date,
    created_at          timestamptz NOT NULL,
    created_by          uuid,
    updated_at          timestamptz NOT NULL,
    updated_by          uuid,
    version             bigint NOT NULL,
    CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);
CREATE INDEX software_license_product_idx ON software_license (tenant_id, lower(product));
CREATE INDEX software_license_valid_idx ON software_license (tenant_id, valid_to);

-- Использование лицензии: на каком устройстве или у какого сотрудника она занята.
CREATE TABLE license_assignment (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenant (id),
    license_id  uuid NOT NULL REFERENCES software_license (id),
    asset_id    uuid REFERENCES asset (id),
    user_id     uuid,
    assigned_by uuid,
    assigned_at timestamptz NOT NULL,
    CHECK ((asset_id IS NULL) <> (user_id IS NULL))
);
CREATE UNIQUE INDEX license_assignment_asset_uq ON license_assignment (license_id, asset_id) WHERE asset_id IS NOT NULL;
CREATE UNIQUE INDEX license_assignment_user_uq ON license_assignment (license_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX license_assignment_asset_idx ON license_assignment (tenant_id, asset_id) WHERE asset_id IS NOT NULL;
CREATE INDEX license_assignment_user_idx ON license_assignment (tenant_id, user_id) WHERE user_id IS NOT NULL;

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['asset_location', 'asset_vendor', 'asset_contract', 'asset', 'asset_event',
                             'asset_ticket', 'software_license', 'license_assignment'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = app_current_tenant()) '
                       'WITH CHECK (tenant_id = app_current_tenant())', t);
    END LOOP;
END $$;

-- Напоминания о продлении по всем арендаторам (AST-04): договоры и лицензии, у которых наступил срок
-- напоминания, а о текущей дате окончания ещё не напоминали. SECURITY DEFINER, как sla_due_instances:
-- наружу выходят только идентификаторы, каждая запись дальше читается в своём арендаторе.
CREATE FUNCTION assets_renewal_queue(p_today date, p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid, kind text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
    (SELECT c.tenant_id, c.id, 'contract' FROM asset_contract c
      WHERE NOT c.terminated AND c.ends_on IS NOT NULL
        AND c.ends_on - c.renewal_notice_days <= p_today AND c.ends_on >= p_today
        AND c.reminded_for IS DISTINCT FROM c.ends_on
      ORDER BY c.ends_on LIMIT p_limit)
    UNION ALL
    (SELECT l.tenant_id, l.id, 'license' FROM software_license l
      WHERE l.valid_to IS NOT NULL
        AND l.valid_to - l.renewal_notice_days <= p_today AND l.valid_to >= p_today
        AND l.reminded_for IS DISTINCT FROM l.valid_to
      ORDER BY l.valid_to LIMIT p_limit)
$$;
REVOKE ALL ON FUNCTION assets_renewal_queue(date, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION assets_renewal_queue(date, int) TO ${app_role};
