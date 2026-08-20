CREATE TABLE model_platform_provider (
    provider VARCHAR(40) PRIMARY KEY,
    encrypted_api_key BYTEA NOT NULL CHECK (octet_length(encrypted_api_key) BETWEEN 16 AND 8192),
    encryption_nonce BYTEA NOT NULL CHECK (octet_length(encryption_nonce) = 12),
    encryption_key_version SMALLINT NOT NULL CHECK (encryption_key_version > 0),
    base_url TEXT NOT NULL,
    model_name VARCHAR(200) NOT NULL CHECK (model_name <> ''),
    vision_capable BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by VARCHAR(40) NOT NULL REFERENCES app_user (username),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE model_personal_provider (
    username VARCHAR(40) NOT NULL REFERENCES app_user (username) ON DELETE CASCADE,
    provider VARCHAR(40) NOT NULL,
    encrypted_api_key BYTEA NOT NULL CHECK (octet_length(encrypted_api_key) BETWEEN 16 AND 8192),
    encryption_nonce BYTEA NOT NULL CHECK (octet_length(encryption_nonce) = 12),
    encryption_key_version SMALLINT NOT NULL CHECK (encryption_key_version > 0),
    base_url TEXT NOT NULL,
    model_name VARCHAR(200) NOT NULL CHECK (model_name <> ''),
    vision_capable BOOLEAN NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (username, provider)
);

CREATE TABLE model_platform_assignment (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    teaching_provider VARCHAR(40) NOT NULL,
    visual_provider VARCHAR(40) NOT NULL,
    answer_provider VARCHAR(40) NOT NULL,
    critic_provider VARCHAR(40) NOT NULL,
    recommendation_provider VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by VARCHAR(40) NOT NULL REFERENCES app_user (username),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE model_personal_assignment (
    username VARCHAR(40) PRIMARY KEY REFERENCES app_user (username) ON DELETE CASCADE,
    teaching_provider VARCHAR(40) NOT NULL,
    visual_provider VARCHAR(40) NOT NULL,
    answer_provider VARCHAR(40) NOT NULL,
    critic_provider VARCHAR(40) NOT NULL,
    recommendation_provider VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE model_configuration_audit (
    id UUID PRIMARY KEY,
    actor_username VARCHAR(40) NOT NULL REFERENCES app_user (username),
    target_scope VARCHAR(16) NOT NULL CHECK (target_scope IN ('PLATFORM', 'PERSONAL')),
    target_username VARCHAR(40),
    provider VARCHAR(40),
    action VARCHAR(40) NOT NULL CHECK (action IN ('PROVIDER_SAVED', 'PROVIDER_REMOVED', 'ASSIGNMENTS_SAVED')),
    resulting_revision BIGINT NOT NULL CHECK (resulting_revision > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    CHECK ((target_scope = 'PLATFORM' AND target_username IS NULL)
        OR (target_scope = 'PERSONAL' AND target_username IS NOT NULL))
);

CREATE INDEX ix_model_configuration_audit_actor_time
    ON model_configuration_audit (actor_username, occurred_at DESC);
CREATE INDEX ix_model_configuration_audit_target_time
    ON model_configuration_audit (target_scope, target_username, occurred_at DESC);
