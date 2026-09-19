-- Shared, durable lock state for scheduled work running on multiple instances.
-- The primary key makes acquisition of a named lock atomic in PostgreSQL.
CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
