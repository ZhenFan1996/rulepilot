ALTER TABLE rule_chunk
    ADD COLUMN embedding VECTOR(64),
    ADD COLUMN embedding_provider VARCHAR(80),
    ADD COLUMN embedded_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_rule_chunk_embedding_metadata CHECK (
        (embedding IS NULL AND embedding_provider IS NULL AND embedded_at IS NULL)
        OR
        (embedding IS NOT NULL AND embedding_provider IS NOT NULL AND embedded_at IS NOT NULL)
    );

CREATE INDEX ix_rule_chunk_embedding_scope
    ON rule_chunk (document_version_id)
    WHERE embedding IS NOT NULL;
