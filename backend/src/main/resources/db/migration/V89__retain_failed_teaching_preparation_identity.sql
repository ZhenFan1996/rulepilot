ALTER TABLE official_rulebook_import_job
    DROP CONSTRAINT ck_official_rulebook_teaching_handoff_shape;

ALTER TABLE official_rulebook_import_job
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
            AND teaching_error_code IS NOT NULL
            AND teaching_handoff_updated_at IS NOT NULL)
    );
