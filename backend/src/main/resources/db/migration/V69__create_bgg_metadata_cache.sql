CREATE TABLE bgg_metadata_cache (
    cache_kind VARCHAR(16) NOT NULL CHECK (cache_kind IN ('HOT', 'DISCOVERY', 'GAME')),
    bgg_id INTEGER NOT NULL CHECK (bgg_id >= 0),
    payload JSONB NOT NULL,
    payload_bytes INTEGER NOT NULL CHECK (payload_bytes > 0 AND payload_bytes <= 524288),
    cached_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL,
    stale_until TIMESTAMPTZ NOT NULL,
    last_accessed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (cache_kind, bgg_id),
    CHECK (fresh_until >= cached_at),
    CHECK (stale_until >= fresh_until),
    CHECK ((cache_kind = 'HOT' AND bgg_id = 0) OR (cache_kind <> 'HOT' AND bgg_id > 0))
);

CREATE INDEX ix_bgg_metadata_cache_stale_until ON bgg_metadata_cache (stale_until);
CREATE INDEX ix_bgg_metadata_cache_last_accessed ON bgg_metadata_cache (last_accessed_at);
