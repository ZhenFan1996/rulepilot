ALTER TABLE visual_rulebook_page_fact
    ADD COLUMN source_dependencies TEXT NOT NULL DEFAULT '[]';

COMMENT ON COLUMN visual_rulebook_page_fact.source_dependencies IS
    'Page-scoped external rule-source titles and explicitly missing teaching obligations; schema-versioned visual evidence.';
