CREATE TABLE feed_versions (
                               id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               source_uri VARCHAR(2048) NOT NULL,
                               checksum VARCHAR(64) NOT NULL UNIQUE,
                               effective_from DATE,
                               imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE routes (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        feed_version_id BIGINT NOT NULL,
                        external_route_id VARCHAR(255) NOT NULL,
                        agency_id VARCHAR(255),
                        short_name VARCHAR(255),
                        long_name VARCHAR(512),
                        route_type SMALLINT NOT NULL,

                        CONSTRAINT fk_routes_feed_version
                            FOREIGN KEY (feed_version_id)
                                REFERENCES feed_versions(id),

                        CONSTRAINT uq_routes_feed_version_external_id
                            UNIQUE (feed_version_id, external_route_id)
);

CREATE TABLE stops (
                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       feed_version_id BIGINT NOT NULL,
                       external_stop_id VARCHAR(255) NOT NULL,
                       stop_name VARCHAR(512) NOT NULL,
                       latitude NUMERIC(9, 6),
                       longitude NUMERIC(9, 6),

                       CONSTRAINT fk_stops_feed_version
                           FOREIGN KEY (feed_version_id)
                               REFERENCES feed_versions(id),

                       CONSTRAINT uq_stops_feed_version_external_id
                           UNIQUE (feed_version_id, external_stop_id)
);

CREATE INDEX idx_routes_short_name ON routes(short_name);
CREATE INDEX idx_stops_stop_name ON stops(stop_name);