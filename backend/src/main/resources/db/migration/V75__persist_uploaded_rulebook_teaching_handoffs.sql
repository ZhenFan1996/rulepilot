CREATE TABLE uploaded_rulebook_teaching_handoff (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL UNIQUE REFERENCES document_version(id) ON DELETE CASCADE,
    owner_username VARCHAR(120) NOT NULL,
    learning_goal VARCHAR(500) NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'WAITING_FOR_DOCUMENT', 'LAUNCHING', 'LAUNCHED', 'FAILED'
    )),
    preparation_run_id UUID NULL REFERENCES assistant_run(id),
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_uploaded_rulebook_teaching_handoff_shape CHECK (
        (state IN ('WAITING_FOR_DOCUMENT', 'LAUNCHING')
            AND preparation_run_id IS NULL
            AND error_code IS NULL)
        OR
        (state = 'LAUNCHED'
            AND preparation_run_id IS NOT NULL
            AND error_code IS NULL)
        OR
        (state = 'FAILED'
            AND preparation_run_id IS NULL
            AND error_code IS NOT NULL)
    )
);

CREATE INDEX ix_uploaded_rulebook_teaching_handoff_owner_recent
    ON uploaded_rulebook_teaching_handoff (owner_username, created_at DESC);

CREATE INDEX ix_uploaded_rulebook_teaching_handoff_waiting
    ON uploaded_rulebook_teaching_handoff (state, created_at)
    WHERE state = 'WAITING_FOR_DOCUMENT';
