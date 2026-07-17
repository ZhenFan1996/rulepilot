CREATE TABLE rule_structure_section (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    section_type VARCHAR(40) NOT NULL,
    content TEXT NOT NULL,
    page_numbers VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, section_type)
);

CREATE INDEX ix_rule_structure_section_version
    ON rule_structure_section (document_version_id, section_type);
