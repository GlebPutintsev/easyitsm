-- Опрос удовлетворённости после закрытия заявки (PRT-06): одна оценка на заявку.
CREATE TABLE survey_response (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenant (id),
    ticket_id     uuid NOT NULL,
    respondent_id uuid NOT NULL,
    score         smallint NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment       text CHECK (comment IS NULL OR char_length(comment) <= 2000),
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    version       bigint NOT NULL,
    UNIQUE (tenant_id, ticket_id)
);

ALTER TABLE survey_response ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON survey_response
    USING (tenant_id = app_current_tenant()) WITH CHECK (tenant_id = app_current_tenant());
