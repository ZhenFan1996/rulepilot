ALTER TABLE assistant_run_budget
    ADD COLUMN activated_at TIMESTAMPTZ,
    ADD COLUMN activation_id UUID,
    ADD CONSTRAINT assistant_run_budget_activation_pair
        CHECK ((activated_at IS NULL) = (activation_id IS NULL));

COMMENT ON COLUMN assistant_run_budget.activated_at IS
    'One-shot worker admission for queued Teaching and Teaching-preparation runs; null for non-queued modes.';

COMMENT ON COLUMN assistant_run_budget.activation_id IS
    'Idempotency identity of the worker delivery that owns queued admission; paired with activated_at.';
