CREATE TABLE visual_rulebook_page_fact (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL,
    page_number INTEGER NOT NULL,
    printed_terms TEXT NOT NULL,
    factual_summary TEXT NOT NULL,
    keywords TEXT NOT NULL,
    CONSTRAINT visual_rulebook_page_fact_document_page_unique UNIQUE (document_version_id, page_number),
    CONSTRAINT visual_rulebook_page_fact_page_number_positive CHECK (page_number > 0)
);

CREATE INDEX visual_rulebook_page_fact_version_page_idx
    ON visual_rulebook_page_fact (document_version_id, page_number);
