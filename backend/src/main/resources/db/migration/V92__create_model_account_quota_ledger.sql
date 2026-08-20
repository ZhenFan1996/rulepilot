CREATE TABLE model_account_quota (
    username VARCHAR(40) PRIMARY KEY REFERENCES app_user (username) ON DELETE CASCADE,
    platform_access_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    monthly_token_limit BIGINT NOT NULL CHECK (monthly_token_limit >= 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by VARCHAR(40) NOT NULL REFERENCES app_user (username),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE model_usage_ledger (
    reservation_id UUID PRIMARY KEY,
    username VARCHAR(40) NOT NULL REFERENCES app_user (username) ON DELETE CASCADE,
    credential_source VARCHAR(16) NOT NULL CHECK (credential_source IN ('PLATFORM', 'PERSONAL')),
    role VARCHAR(24) NOT NULL CHECK (role IN ('TEACHING', 'VISUAL', 'ANSWER', 'CRITIC', 'RECOMMENDATION')),
    provider VARCHAR(40) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    operation VARCHAR(120) NOT NULL,
    period_start DATE NOT NULL,
    reserved_tokens BIGINT NOT NULL CHECK (reserved_tokens > 0),
    prompt_tokens BIGINT CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT CHECK (completion_tokens >= 0),
    charged_tokens BIGINT NOT NULL DEFAULT 0 CHECK (charged_tokens >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED')),
    outcome VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    settled_at TIMESTAMPTZ,
    CHECK ((status = 'RESERVED' AND settled_at IS NULL)
        OR (status <> 'RESERVED' AND settled_at IS NOT NULL)),
    CHECK ((credential_source = 'PLATFORM') OR charged_tokens = 0)
);

CREATE INDEX ix_model_usage_ledger_account_period
    ON model_usage_ledger (username, period_start, credential_source, status);
CREATE INDEX ix_model_usage_ledger_created_at
    ON model_usage_ledger (created_at DESC);
