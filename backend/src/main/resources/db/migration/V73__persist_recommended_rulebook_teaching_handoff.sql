ALTER TABLE official_rulebook_import_job
    DROP CONSTRAINT IF EXISTS official_rulebook_import_job_stage_check;

ALTER TABLE official_rulebook_import_job
    ADD CONSTRAINT ck_official_rulebook_import_stage CHECK (stage IN (
        'QUEUED', 'CONNECTING', 'DOWNLOADING', 'COMPRESSING',
        'VERIFYING_FILE', 'SAVING', 'COMPLETED', 'FAILED'
    ));

ALTER TABLE official_rulebook_import_job
    ADD COLUMN teaching_handoff_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN teaching_learning_goal VARCHAR(500) NULL,
    ADD COLUMN teaching_preparation_run_id UUID NULL REFERENCES assistant_run(id),
    ADD COLUMN teaching_error_code VARCHAR(64) NULL,
    ADD COLUMN teaching_handoff_updated_at TIMESTAMPTZ NULL;

ALTER TABLE official_rulebook_import_job
    ADD CONSTRAINT ck_official_rulebook_teaching_handoff_state CHECK (teaching_handoff_state IN (
        'NOT_REQUESTED', 'WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'
    )),
    ADD CONSTRAINT ck_official_rulebook_teaching_handoff_shape CHECK (
        (teaching_handoff_state = 'NOT_REQUESTED'
            AND teaching_learning_goal IS NULL
            AND teaching_preparation_run_id IS NULL
            AND teaching_error_code IS NULL
            AND teaching_handoff_updated_at IS NULL)
        OR
        (teaching_handoff_state IN ('WAITING_FOR_DOCUMENT', 'LAUNCHING')
            AND teaching_preparation_run_id IS NULL
            AND teaching_error_code IS NULL
            AND teaching_handoff_updated_at IS NOT NULL)
        OR
        (teaching_handoff_state = 'LAUNCHED'
            AND teaching_preparation_run_id IS NOT NULL
            AND teaching_error_code IS NULL
            AND teaching_handoff_updated_at IS NOT NULL)
        OR
        (teaching_handoff_state = 'FAILED'
            AND teaching_preparation_run_id IS NULL
            AND teaching_error_code IS NOT NULL
            AND teaching_handoff_updated_at IS NOT NULL)
    );

CREATE INDEX ix_official_rulebook_import_teaching_waiting
    ON official_rulebook_import_job (teaching_handoff_state, created_at)
    WHERE teaching_handoff_state = 'WAITING_FOR_DOCUMENT';
