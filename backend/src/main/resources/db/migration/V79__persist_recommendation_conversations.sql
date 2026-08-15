CREATE TABLE recommendation_conversation (
    id UUID PRIMARY KEY,
    owner_username VARCHAR(40) NOT NULL REFERENCES app_user (username) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    state_json JSONB NOT NULL,
    last_client_turn_id UUID,
    last_request_fingerprint VARCHAR(64),
    last_response_json JSONB,
    last_response_locale VARCHAR(16),
    active_client_turn_id UUID,
    active_request_fingerprint VARCHAR(64),
    active_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recommendation_conversation_owner UNIQUE (owner_username),
    CONSTRAINT ck_recommendation_conversation_last_turn CHECK (
        (last_client_turn_id IS NULL
            AND last_request_fingerprint IS NULL
            AND last_response_json IS NULL
            AND last_response_locale IS NULL)
        OR
        (last_client_turn_id IS NOT NULL
            AND last_request_fingerprint IS NOT NULL
            AND last_response_json IS NOT NULL
            AND last_response_locale IS NOT NULL)
    ),
    CONSTRAINT ck_recommendation_conversation_active_turn CHECK (
        (active_client_turn_id IS NULL
            AND active_request_fingerprint IS NULL
            AND active_started_at IS NULL)
        OR
        (active_client_turn_id IS NOT NULL
            AND active_request_fingerprint IS NOT NULL
            AND active_started_at IS NOT NULL)
    )
);

CREATE INDEX idx_recommendation_conversation_updated
    ON recommendation_conversation (updated_at);
