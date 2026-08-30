ALTER TABLE assistant_run_budget
    ADD COLUMN token_limit_enforced BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE assistant_run_budget budget
SET token_limit_enforced = FALSE
FROM assistant_run run
WHERE run.id = budget.assistant_run_id
  AND run.mode IN ('TEACHING', 'TEACHING_PREPARATION', 'VISUAL_ENRICHMENT');

COMMENT ON COLUMN assistant_run_budget.token_limit_enforced IS
    'False for long-running teaching work: token usage remains observable while deadline and cancellation own termination.';
