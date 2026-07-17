CREATE TABLE document_page (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    text_content TEXT NOT NULL,
    character_count INTEGER NOT NULL CHECK (character_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, page_number)
);

CREATE INDEX ix_document_page_version ON document_page (document_version_id, page_number);
