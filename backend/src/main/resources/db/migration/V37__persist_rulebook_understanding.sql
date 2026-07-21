CREATE TABLE rulebook_page_block (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    block_index INTEGER NOT NULL CHECK (block_index >= 0),
    reading_order INTEGER NOT NULL CHECK (reading_order >= 0),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    x INTEGER NOT NULL CHECK (x >= 0 AND x <= 1000),
    y INTEGER NOT NULL CHECK (y >= 0 AND y <= 1000),
    width INTEGER NOT NULL CHECK (width > 0 AND width <= 1000),
    height INTEGER NOT NULL CHECK (height > 0 AND height <= 1000),
    heading_block_index INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, page_number, block_index),
    CHECK (x + width <= 1000),
    CHECK (y + height <= 1000)
);

CREATE INDEX ix_rulebook_page_block_version_page
    ON rulebook_page_block (document_version_id, page_number, block_index);

CREATE TABLE rulebook_terminology (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    term VARCHAR(120) NOT NULL,
    normalized_term VARCHAR(120) NOT NULL,
    evidence_block_id UUID NOT NULL REFERENCES rulebook_page_block(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, normalized_term)
);

CREATE INDEX ix_rulebook_terminology_version
    ON rulebook_terminology (document_version_id, normalized_term);

CREATE TABLE rulebook_inventory_item (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    inventory_key VARCHAR(80) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    label VARCHAR(200) NOT NULL,
    evidence_block_id UUID NOT NULL REFERENCES rulebook_page_block(id) ON DELETE RESTRICT,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    block_index INTEGER NOT NULL CHECK (block_index >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, inventory_key)
);

CREATE INDEX ix_rulebook_inventory_version_page
    ON rulebook_inventory_item (document_version_id, page_number, block_index);

CREATE TABLE rulebook_coverage_ledger (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    inventory_item_id UUID NOT NULL REFERENCES rulebook_inventory_item(id) ON DELETE CASCADE,
    state VARCHAR(20) NOT NULL,
    exclusion_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, inventory_item_id),
    CHECK (
        (state = 'EXCLUDED' AND exclusion_reason IS NOT NULL AND btrim(exclusion_reason) <> '')
        OR (state <> 'EXCLUDED' AND exclusion_reason IS NULL)
    )
);

CREATE INDEX ix_rulebook_coverage_ledger_version_state
    ON rulebook_coverage_ledger (document_version_id, state);
