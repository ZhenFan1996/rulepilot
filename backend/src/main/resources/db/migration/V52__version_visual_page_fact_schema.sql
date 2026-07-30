ALTER TABLE visual_rulebook_page_fact
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE visual_rulebook_page_fact
    ADD CONSTRAINT ck_visual_page_fact_schema_version CHECK (schema_version > 0);

COMMENT ON COLUMN visual_rulebook_page_fact.schema_version IS
    'Version of the visual transcription contract. Stale facts are recataloged before reuse.';
