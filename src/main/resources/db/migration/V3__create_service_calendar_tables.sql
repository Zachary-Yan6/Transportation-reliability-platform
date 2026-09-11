CREATE TABLE service_calendars (
                                   id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                   feed_version_id BIGINT NOT NULL,
                                   external_service_id VARCHAR(255) NOT NULL,

                                   monday BOOLEAN NOT NULL,
                                   tuesday BOOLEAN NOT NULL,
                                   wednesday BOOLEAN NOT NULL,
                                   thursday BOOLEAN NOT NULL,
                                   friday BOOLEAN NOT NULL,
                                   saturday BOOLEAN NOT NULL,
                                   sunday BOOLEAN NOT NULL,

                                   start_date DATE NOT NULL,
                                   end_date DATE NOT NULL,

                                   CONSTRAINT fk_service_calendars_feed_version
                                       FOREIGN KEY (feed_version_id)
                                           REFERENCES feed_versions(id),

                                   CONSTRAINT uq_service_calendars_feed_version_service
                                       UNIQUE (feed_version_id, external_service_id),

                                   CONSTRAINT chk_service_calendars_date_range
                                       CHECK (start_date <= end_date)
);

CREATE TABLE service_calendar_dates (
                                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                        feed_version_id BIGINT NOT NULL,
                                        external_service_id VARCHAR(255) NOT NULL,
                                        service_date DATE NOT NULL,
                                        exception_type SMALLINT NOT NULL,

                                        CONSTRAINT fk_service_calendar_dates_feed_version
                                            FOREIGN KEY (feed_version_id)
                                                REFERENCES feed_versions(id),

                                        CONSTRAINT uq_service_calendar_dates_version_service_date
                                            UNIQUE (feed_version_id, external_service_id, service_date),

                                        CONSTRAINT chk_service_calendar_dates_exception_type
                                            CHECK (exception_type IN (1, 2))
);

CREATE INDEX idx_service_calendar_dates_lookup
    ON service_calendar_dates(feed_version_id, external_service_id, service_date);