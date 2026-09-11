ALTER TABLE feed_versions
    ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'IMPORTING';

-- The project already has one manually verified feed. Preserve it as the
-- active source until a fully imported replacement explicitly takes over.
UPDATE feed_versions
SET lifecycle_status = 'ACTIVE'
WHERE id = (
    SELECT id
    FROM feed_versions
    ORDER BY imported_at DESC, id DESC
    LIMIT 1
);

CREATE UNIQUE INDEX uq_feed_versions_active
    ON feed_versions(lifecycle_status)
    WHERE lifecycle_status = 'ACTIVE';
