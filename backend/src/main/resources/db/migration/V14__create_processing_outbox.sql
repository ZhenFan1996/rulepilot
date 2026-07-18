CREATE TABLE processing_job (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    pipeline_version VARCHAR(40) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (document_version_id, pipeline_version),
    CONSTRAINT ck_processing_job_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_outbox_event_ready
    ON outbox_event (next_attempt_at, occurred_at)
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_event_aggregate
    ON outbox_event (aggregate_type, aggregate_id, occurred_at);
