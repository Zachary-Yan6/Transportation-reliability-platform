CREATE TABLE trips (
                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       feed_version_id BIGINT NOT NULL,
                       route_id BIGINT NOT NULL,
                       external_trip_id VARCHAR(255) NOT NULL,
                       service_id VARCHAR(255) NOT NULL,
                       trip_headsign VARCHAR(512),
                       direction_id SMALLINT,

                       CONSTRAINT fk_trips_feed_version
                           FOREIGN KEY (feed_version_id)
                               REFERENCES feed_versions(id),

                       CONSTRAINT fk_trips_route
                           FOREIGN KEY (route_id)
                               REFERENCES routes(id),

                       CONSTRAINT uq_trips_feed_version_external_id
                           UNIQUE (feed_version_id, external_trip_id)
);

CREATE TABLE stop_times (
                            id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            trip_id BIGINT NOT NULL,
                            stop_id BIGINT NOT NULL,
                            stop_sequence INTEGER NOT NULL,
                            arrival_seconds INTEGER,
                            departure_seconds INTEGER,
                            pickup_type SMALLINT,
                            drop_off_type SMALLINT,

                            CONSTRAINT fk_stop_times_trip
                                FOREIGN KEY (trip_id)
                                    REFERENCES trips(id),

                            CONSTRAINT fk_stop_times_stop
                                FOREIGN KEY (stop_id)
                                    REFERENCES stops(id),

                            CONSTRAINT ck_stop_times_sequence
                                CHECK (stop_sequence > 0),

                            CONSTRAINT ck_stop_times_arrival_seconds
                                CHECK (arrival_seconds IS NULL OR arrival_seconds >= 0),

                            CONSTRAINT ck_stop_times_departure_seconds
                                CHECK (departure_seconds IS NULL OR departure_seconds >= 0),

                            CONSTRAINT uq_stop_times_trip_sequence
                                UNIQUE (trip_id, stop_sequence)
);

CREATE INDEX idx_trips_route_id ON trips(route_id);
CREATE INDEX idx_stop_times_stop_id ON stop_times(stop_id);