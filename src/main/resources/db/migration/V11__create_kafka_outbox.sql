CREATE TABLE kafka_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    topic VARCHAR(120) NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CONSTRAINT chk_kafka_outbox_status
        CHECK (status IN ('PENDING', 'RETRY', 'SENT', 'DEAD')),
    CONSTRAINT uq_kafka_outbox_topic_event UNIQUE (topic, event_id)
);

CREATE INDEX idx_kafka_outbox_due_events
    ON kafka_outbox (status, next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY');
