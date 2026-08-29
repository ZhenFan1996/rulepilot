ALTER TABLE assistant_run_step
    ALTER COLUMN step_summary TYPE TEXT;

ALTER TABLE assistant_run_activity
    ALTER COLUMN operation_name TYPE TEXT,
    ALTER COLUMN summary TYPE TEXT;
