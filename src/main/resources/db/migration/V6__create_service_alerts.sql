CREATE TABLE service_alerts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    route_id BIGINT NOT NULL,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,

    first_detected_at TIMESTAMPTZ NOT NULL,
    last_detected_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,

    CONSTRAINT fk_service_alerts_route
        FOREIGN KEY (route_id)
            REFERENCES routes(id),

    CONSTRAINT chk_service_alerts_type
        CHECK (alert_type IN ('DELAY_ANOMALY')),

    CONSTRAINT chk_service_alerts_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),

    CONSTRAINT chk_service_alerts_status
        CHECK (status IN ('ACTIVE', 'RESOLVED'))
);

-- A route can have at most one active alert of each type.
CREATE UNIQUE INDEX uq_service_alerts_active_route_type
    ON service_alerts(route_id, alert_type)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_service_alerts_status_last_detected
    ON service_alerts(status, last_detected_at DESC);
