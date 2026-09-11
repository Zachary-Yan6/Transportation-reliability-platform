CREATE TABLE trip_stop_delay_observations (
                                              id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

                                              event_id UUID NOT NULL UNIQUE,

                                              feed_version_id BIGINT NOT NULL,
                                              trip_id BIGINT NOT NULL,
                                              stop_id BIGINT NOT NULL,

                                              stop_sequence INTEGER NOT NULL,
                                              delay_seconds INTEGER NOT NULL,

                                              observed_at TIMESTAMPTZ NOT NULL,
                                              received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                              CONSTRAINT fk_delay_observations_feed_version
                                                  FOREIGN KEY (feed_version_id)
                                                      REFERENCES feed_versions(id),

                                              CONSTRAINT fk_delay_observations_trip
                                                  FOREIGN KEY (trip_id)
                                                      REFERENCES trips(id),

                                              CONSTRAINT fk_delay_observations_stop
                                                  FOREIGN KEY (stop_id)
                                                      REFERENCES stops(id),

                                              CONSTRAINT chk_delay_observations_stop_sequence
                                                  CHECK (stop_sequence > 0)
);

CREATE INDEX idx_delay_observations_trip_time
    ON trip_stop_delay_observations(trip_id, observed_at DESC);

CREATE INDEX idx_delay_observations_stop_time
    ON trip_stop_delay_observations(stop_id, observed_at DESC);