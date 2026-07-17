CREATE TABLE rule_document (
    id UUID PRIMARY KEY,
    game_edition_id UUID NOT NULL REFERENCES game_edition(id) ON DELETE RESTRICT,
    title VARCHAR(160) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_rule_document_scope
    ON rule_document (game_edition_id, lower(title), source_type);

CREATE TABLE document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rule_document(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    original_filename VARCHAR(255) NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    checksum VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    content_type VARCHAR(100) NOT NULL,
    processing_status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_id, version_number),
    UNIQUE (document_id, checksum)
);

CREATE INDEX ix_rule_document_edition ON rule_document (game_edition_id);
CREATE INDEX ix_document_version_document ON document_version (document_id, version_number DESC);
