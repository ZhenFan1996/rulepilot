CREATE TABLE rule_chunk (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    section_type VARCHAR(40) NOT NULL,
    heading TEXT NOT NULL,
    content TEXT NOT NULL,
    page_from INTEGER NOT NULL CHECK (page_from > 0),
    page_to INTEGER NOT NULL CHECK (page_to >= page_from),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content_tsv TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(heading, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(content, '')), 'B')
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX ix_rule_chunk_content_tsv ON rule_chunk USING GIN (content_tsv);
CREATE INDEX ix_rule_chunk_version_page ON rule_chunk (document_version_id, page_from);

INSERT INTO rule_chunk (
    id, document_version_id, section_type, heading, content,
    page_from, page_to, chunk_index, created_at
)
SELECT
    id,
    document_version_id,
    section_type,
    replace(initcap(replace(lower(section_type), '_', ' ')), 'Setup', 'Setup'),
    content,
    split_part(page_numbers, ',', 1)::integer,
    split_part(page_numbers, ',', array_length(string_to_array(page_numbers, ','), 1))::integer,
    row_number() OVER (PARTITION BY document_version_id ORDER BY section_type) - 1,
    created_at
FROM rule_structure_section
WHERE page_numbers <> '';
