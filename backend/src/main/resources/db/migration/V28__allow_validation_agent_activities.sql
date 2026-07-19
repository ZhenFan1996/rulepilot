ALTER TABLE assistant_run_activity
    DROP CONSTRAINT assistant_run_activity_activity_type_check;

ALTER TABLE assistant_run_activity
    ADD CONSTRAINT assistant_run_activity_activity_type_check
    CHECK (activity_type IN ('TOOL', 'MODEL', 'CRITIC', 'VALIDATION'));
