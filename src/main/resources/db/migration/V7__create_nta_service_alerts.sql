CREATE TABLE nta_service_alerts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_alert_id VARCHAR(255) NOT NULL UNIQUE,
    header_text TEXT NOT NULL,
    description_text TEXT,
    cause VARCHAR(64),
    effect VARCHAR(64),
    severity_level VARCHAR(64),
    active_from TIMESTAMPTZ,
    active_until TIMESTAMPTZ,
    external_route_ids TEXT NOT NULL DEFAULT '',
    external_stop_ids TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_seen_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_nta_service_alerts_status CHECK (status IN ('ACTIVE', 'RESOLVED'))
);

CREATE INDEX idx_nta_service_alerts_status_seen
    ON nta_service_alerts(status, last_seen_at DESC);
