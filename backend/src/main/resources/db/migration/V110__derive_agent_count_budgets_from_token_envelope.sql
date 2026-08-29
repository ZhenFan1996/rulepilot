UPDATE assistant_run_budget
SET max_steps = max_tokens,
    max_tool_calls = GREATEST(max_tokens, used_tool_calls),
    max_model_calls = 2147483647;

ALTER TABLE assistant_run_budget
    ALTER COLUMN max_steps SET DEFAULT 2147483647,
    ALTER COLUMN max_tool_calls SET DEFAULT 2147483647,
    ALTER COLUMN max_model_calls SET DEFAULT 2147483647;

COMMENT ON COLUMN assistant_run_budget.max_steps IS
    'Safety ceiling derived from the persisted token envelope; not a product-authored workflow length.';
COMMENT ON COLUMN assistant_run_budget.max_tool_calls IS
    'Safety ceiling derived from the persisted token envelope; not a hand-written tool-call limit.';
COMMENT ON COLUMN assistant_run_budget.max_model_calls IS
    'Compatibility-only column retained for one rollback window; current code records model calls observationally.';
