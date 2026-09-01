CREATE TABLE bgg_catalog_cover_prewarm_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    snapshot_sha256 CHAR(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    format_version VARCHAR(80) NOT NULL CHECK (format_version ~ '^[a-z0-9][a-z0-9._-]{0,79}$'),
    next_offset INTEGER NOT NULL CHECK (next_offset >= 0),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);

COMMENT ON TABLE bgg_catalog_cover_prewarm_state IS
    'Independent progress for variant-aware BGG cover assets; never rewinds metadata or translation cursors.';
