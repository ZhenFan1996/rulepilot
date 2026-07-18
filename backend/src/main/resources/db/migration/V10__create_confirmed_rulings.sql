CREATE TABLE confirmed_ruling (
    id UUID PRIMARY KEY,
    edition_id UUID NOT NULL REFERENCES game_edition(id) ON DELETE RESTRICT,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE RESTRICT,
    expansion_set_hash CHAR(64) NOT NULL,
    original_question TEXT NOT NULL,
    normalized_question TEXT NOT NULL,
    normalized_question_hash CHAR(64) NOT NULL,
    short_verdict TEXT NOT NULL,
    explanation TEXT NOT NULL,
    confidence VARCHAR(20) NOT NULL,
    official BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_confirmed_ruling_status CHECK (status IN ('CONFIRMED', 'SUPERSEDED')),
    CONSTRAINT ck_confirmed_ruling_confidence CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE TABLE confirmed_ruling_expansion (
    ruling_id UUID NOT NULL REFERENCES confirmed_ruling(id) ON DELETE CASCADE,
    expansion_id UUID NOT NULL REFERENCES expansion(id) ON DELETE RESTRICT,
    PRIMARY KEY (ruling_id, expansion_id)
);

CREATE TABLE confirmed_ruling_citation (
    ruling_id UUID NOT NULL REFERENCES confirmed_ruling(id) ON DELETE CASCADE,
    citation_order INTEGER NOT NULL CHECK (citation_order >= 0),
    chunk_id UUID NOT NULL REFERENCES rule_chunk(id) ON DELETE RESTRICT,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE RESTRICT,
    section_type VARCHAR(40) NOT NULL,
    heading TEXT NOT NULL,
    excerpt TEXT NOT NULL,
    page_from INTEGER NOT NULL CHECK (page_from > 0),
    page_to INTEGER NOT NULL CHECK (page_to >= page_from),
    PRIMARY KEY (ruling_id, citation_order),
    UNIQUE (ruling_id, chunk_id)
);

CREATE TABLE confirmed_ruling_exception (
    ruling_id UUID NOT NULL REFERENCES confirmed_ruling(id) ON DELETE CASCADE,
    exception_order INTEGER NOT NULL CHECK (exception_order >= 0),
    exception_text TEXT NOT NULL,
    PRIMARY KEY (ruling_id, exception_order)
);

CREATE UNIQUE INDEX ux_confirmed_ruling_active_scope_question
    ON confirmed_ruling (
        edition_id,
        document_version_id,
        expansion_set_hash,
        normalized_question_hash
    )
    WHERE status = 'CONFIRMED';

CREATE INDEX ix_confirmed_ruling_owner_created
    ON confirmed_ruling (created_by, created_at DESC);
