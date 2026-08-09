CREATE TABLE official_rulebook_import_job (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(120) NOT NULL,
    edition_id UUID NULL REFERENCES game_edition(id) ON DELETE SET NULL,
    title VARCHAR(160) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_url VARCHAR(2000) NOT NULL,
    stage VARCHAR(32) NOT NULL CHECK (stage IN (
        'QUEUED', 'CONNECTING', 'DOWNLOADING', 'VERIFYING_FILE', 'SAVING', 'COMPLETED', 'FAILED'
    )),
    downloaded_bytes BIGINT NOT NULL DEFAULT 0 CHECK (downloaded_bytes >= 0),
    total_bytes BIGINT NULL CHECK (total_bytes IS NULL OR total_bytes > 0),
    document_version_id UUID NULL REFERENCES document_version(id) ON DELETE SET NULL,
    duplicate BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    CHECK ((stage IN ('COMPLETED', 'FAILED')) = (completed_at IS NOT NULL)),
    CHECK ((stage = 'FAILED') = (error_code IS NOT NULL)),
    CHECK (stage <> 'COMPLETED' OR document_version_id IS NOT NULL)
);

CREATE INDEX ix_official_rulebook_import_owner_recent
    ON official_rulebook_import_job (owner_username, created_at DESC);

CREATE UNIQUE INDEX ux_official_rulebook_import_active_source
    ON official_rulebook_import_job (owner_username, source_url)
    WHERE stage NOT IN ('COMPLETED', 'FAILED');
