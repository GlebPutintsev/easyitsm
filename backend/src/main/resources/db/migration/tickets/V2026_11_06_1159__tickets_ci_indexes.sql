-- Открытые заявки по КЕ и услуге для анализа влияния CMDB (CFG-09): TicketQueries.openForObjects.
CREATE INDEX ticket_ci_idx ON ticket (ci_id, created_at DESC) WHERE ci_id IS NOT NULL;
CREATE INDEX ticket_service_idx ON ticket (service_id, created_at DESC) WHERE service_id IS NOT NULL;
