ALTER TABLE uploaded_rulebook_teaching_handoff
    ADD COLUMN automatic_recovery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reconciled_at TIMESTAMPTZ NULL;

ALTER TABLE uploaded_rulebook_teaching_handoff
    DROP CONSTRAINT ck_uploaded_rulebook_teaching_handoff_shape;

ALTER TABLE uploaded_rulebook_teaching_handoff
    ADD CONSTRAINT ck_uploaded_rulebook_teaching_handoff_shape CHECK (
        (state IN ('WAITING_FOR_DOCUMENT', 'LAUNCHING')
            AND preparation_run_id IS NULL
            AND error_code IS NULL)
        OR
        (state = 'LAUNCHED'
            AND preparation_run_id IS NOT NULL
            AND error_code IS NULL)
        OR
        (state = 'FAILED'
            AND error_code IS NOT NULL)
    );

CREATE INDEX ix_uploaded_rulebook_teaching_handoff_unreconciled
    ON uploaded_rulebook_teaching_handoff (updated_at)
    WHERE state = 'LAUNCHED' AND reconciled_at IS NULL;
