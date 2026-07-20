UPDATE assistant_run_activity activity
SET outcome = CASE
        WHEN run.last_error_code = 'AGENT_CANCELLED' THEN 'REJECTED'
        ELSE 'FAILED'
    END,
    latency_ms = GREATEST(
        0,
        EXTRACT(EPOCH FROM (run.completed_at - activity.occurred_at)) * 1000
    )::BIGINT,
    summary = CASE
        WHEN run.last_error_code = 'AGENT_CANCELLED' THEN 'Work stopped by the user'
        ELSE 'Work stopped before audit completion'
    END
FROM assistant_run run
WHERE activity.assistant_run_id = run.id
  AND activity.outcome = 'RUNNING'
  AND run.completed_at IS NOT NULL;
