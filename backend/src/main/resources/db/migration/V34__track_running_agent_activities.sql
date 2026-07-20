ALTER TABLE assistant_run_activity
    DROP CONSTRAINT assistant_run_activity_outcome_check;

ALTER TABLE assistant_run_activity
    ADD CONSTRAINT assistant_run_activity_outcome_check
    CHECK (outcome IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED'));
