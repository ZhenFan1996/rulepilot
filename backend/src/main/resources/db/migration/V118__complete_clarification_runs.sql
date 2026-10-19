ALTER TABLE assistant_run DROP CONSTRAINT ck_assistant_run_completion;
UPDATE assistant_run SET completed_at = updated_at
WHERE state = 'NEED_CLARIFICATION' AND completed_at IS NULL;
ALTER TABLE assistant_run ADD CONSTRAINT ck_assistant_run_completion CHECK (
    (state IN ('NEED_CLARIFICATION', 'INSUFFICIENT_EVIDENCE', 'COMPLETED', 'FAILED', 'DEGRADED'))
        = (completed_at IS NOT NULL)
);
