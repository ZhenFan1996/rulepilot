-- Different embedding providers use different vector dimensions. The provider id stored beside each
-- vector keeps query and document vectors in the same embedding space.
ALTER TABLE rule_chunk
    ALTER COLUMN embedding TYPE VECTOR
    USING embedding::vector;
