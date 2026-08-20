CREATE TABLE bgg_metadata_prewarm_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    snapshot_sha256 CHAR(64) NOT NULL CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    metadata_next_offset INTEGER NOT NULL CHECK (metadata_next_offset >= 0),
    translation_next_offset INTEGER NOT NULL CHECK (translation_next_offset >= 0),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((lease_id IS NULL) = (lease_until IS NULL))
);
