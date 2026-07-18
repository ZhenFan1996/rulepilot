CREATE TABLE assistant_run (
    id UUID PRIMARY KEY,
    mode VARCHAR(30) NOT NULL,
    subject_id UUID NOT NULL,
    owner_username VARCHAR(120) NOT NULL,
    state VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CONSTRAINT ck_assistant_run_mode CHECK (mode IN ('TEACHING', 'QUESTION_ANSWER')),
    CONSTRAINT ck_assistant_run_state CHECK (state IN (
        'RECEIVED', 'DOCUMENT_READINESS', 'LESSON_PLANNING', 'QUESTION_UNDERSTANDING',
        'NEED_CLARIFICATION', 'RETRIEVAL_PLANNING', 'RETRIEVING', 'VERIFYING_EVIDENCE',
        'INSUFFICIENT_EVIDENCE', 'LESSON_COMPOSITION', 'ANSWER_COMPOSITION', 'MEDIA_PACKAGING',
        'CRITIQUING', 'COMPLETED', 'FAILED', 'DEGRADED'
    )),
    CONSTRAINT ck_assistant_run_completion CHECK (
        (state IN ('INSUFFICIENT_EVIDENCE', 'COMPLETED', 'FAILED', 'DEGRADED')) = (completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_assistant_run_failure CHECK ((state = 'FAILED') = (last_error_code IS NOT NULL))
);

CREATE TABLE assistant_run_step (
    id UUID PRIMARY KEY,
    assistant_run_id UUID NOT NULL REFERENCES assistant_run(id) ON DELETE CASCADE,
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    from_state VARCHAR(40),
    to_state VARCHAR(40) NOT NULL,
    step_summary VARCHAR(240) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (assistant_run_id, sequence_number)
);

CREATE INDEX ix_assistant_run_owner_updated
    ON assistant_run (owner_username, updated_at DESC);

CREATE INDEX ix_assistant_run_subject
    ON assistant_run (mode, subject_id, created_at DESC);
