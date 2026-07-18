CREATE TABLE assistant_run_budget (
    assistant_run_id UUID PRIMARY KEY REFERENCES assistant_run(id) ON DELETE CASCADE,
    max_steps INTEGER NOT NULL CHECK (max_steps > 0),
    max_tool_calls INTEGER NOT NULL CHECK (max_tool_calls > 0),
    max_model_calls INTEGER NOT NULL CHECK (max_model_calls > 0),
    max_tokens INTEGER NOT NULL CHECK (max_tokens > 0),
    used_tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (used_tool_calls >= 0),
    used_model_calls INTEGER NOT NULL DEFAULT 0 CHECK (used_model_calls >= 0),
    used_tokens INTEGER NOT NULL DEFAULT 0 CHECK (used_tokens >= 0),
    deadline_at TIMESTAMPTZ NOT NULL,
    cancellation_requested_at TIMESTAMPTZ,
    CONSTRAINT ck_assistant_budget_tool_calls CHECK (used_tool_calls <= max_tool_calls),
    CONSTRAINT ck_assistant_budget_model_calls CHECK (used_model_calls <= max_model_calls)
);

CREATE TABLE assistant_run_activity (
    id UUID PRIMARY KEY,
    assistant_run_id UUID NOT NULL REFERENCES assistant_run(id) ON DELETE CASCADE,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    activity_type VARCHAR(20) NOT NULL CHECK (activity_type IN ('TOOL', 'MODEL', 'CRITIC')),
    operation_name VARCHAR(80) NOT NULL,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'REJECTED')),
    estimated_input_tokens INTEGER NOT NULL CHECK (estimated_input_tokens >= 0),
    estimated_output_tokens INTEGER NOT NULL CHECK (estimated_output_tokens >= 0),
    latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
    summary VARCHAR(240) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (assistant_run_id, sequence_number)
);

CREATE INDEX ix_assistant_run_activity_run
    ON assistant_run_activity (assistant_run_id, sequence_number);
