ALTER TABLE document_version
    ADD COLUMN rule_data_version BIGINT NOT NULL DEFAULT 1
    CHECK (rule_data_version > 0);
