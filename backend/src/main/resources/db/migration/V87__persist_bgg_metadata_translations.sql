CREATE TABLE bgg_metadata_translation (
    bgg_id INTEGER NOT NULL CHECK (bgg_id > 0),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    locale VARCHAR(16) NOT NULL CHECK (locale = 'zh-CN'),
    payload JSONB NOT NULL,
    payload_bytes INTEGER NOT NULL CHECK (payload_bytes > 0 AND payload_bytes <= 131072),
    translated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bgg_id, source_sha256)
);

CREATE INDEX ix_bgg_metadata_translation_translated_at
    ON bgg_metadata_translation (translated_at);
