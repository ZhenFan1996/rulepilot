CREATE TABLE processing_stage_execution (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id),
    processing_job_id UUID NOT NULL REFERENCES processing_job(id),
    stage VARCHAR(32) NOT NULL,
    pipeline_version VARCHAR(32) NOT NULL,
    first_event_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_processing_stage_business_key
        UNIQUE (document_version_id, stage, pipeline_version),
    CONSTRAINT ck_processing_stage_name CHECK (stage IN ('PARSE', 'CHUNK', 'EMBED')),
    CONSTRAINT ck_processing_stage_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_processing_stage_job
    ON processing_stage_execution (processing_job_id, started_at);
