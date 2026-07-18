ALTER TABLE processing_stage_execution
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN last_error_code VARCHAR(64);

ALTER TABLE processing_stage_execution
    ADD CONSTRAINT ck_processing_stage_attempt_count CHECK (attempt_count BETWEEN 1 AND 100);
