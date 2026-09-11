CREATE TABLE realtime_ingestion_runs (
                                         id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

                                         feed_version_id BIGINT NOT NULL,
                                         target_external_route_id VARCHAR(255),

                                         feed_timestamp TIMESTAMPTZ,
                                         started_at TIMESTAMPTZ NOT NULL,
                                         finished_at TIMESTAMPTZ NOT NULL,

                                         scanned_stop_time_updates INTEGER NOT NULL DEFAULT 0,
                                         published_event_count INTEGER NOT NULL DEFAULT 0,
                                         skipped_update_count INTEGER NOT NULL DEFAULT 0,

                                         status VARCHAR(16) NOT NULL,
                                         error_message TEXT,

                                         CONSTRAINT fk_realtime_ingestion_runs_feed_version
                                             FOREIGN KEY (feed_version_id)
                                                 REFERENCES feed_versions(id),

                                         CONSTRAINT chk_realtime_ingestion_runs_status
                                             CHECK (status IN ('SUCCESS', 'FAILED')),

                                         CONSTRAINT chk_realtime_ingestion_runs_counts
                                             CHECK (
                                                 scanned_stop_time_updates >= 0
                                                     AND published_event_count >= 0
                                                     AND skipped_update_count >= 0
                                                 )
);

CREATE INDEX idx_realtime_ingestion_runs_started_at
    ON realtime_ingestion_runs(started_at DESC);

CREATE INDEX idx_realtime_ingestion_runs_status_started_at
    ON realtime_ingestion_runs(status, started_at DESC);