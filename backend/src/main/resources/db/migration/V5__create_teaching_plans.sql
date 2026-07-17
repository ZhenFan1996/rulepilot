CREATE TABLE teaching_plan (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    player_count INTEGER NOT NULL CHECK (player_count BETWEEN 1 AND 20),
    beginner_count INTEGER NOT NULL CHECK (beginner_count BETWEEN 0 AND player_count),
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 2 AND 180),
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_teaching_plan_version_created
    ON teaching_plan (document_version_id, created_at DESC);

CREATE TABLE teaching_plan_section (
    id UUID PRIMARY KEY,
    teaching_plan_id UUID NOT NULL REFERENCES teaching_plan(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position > 0),
    section_type VARCHAR(50) NOT NULL,
    required BOOLEAN NOT NULL,
    evidence_available BOOLEAN NOT NULL,
    source_pages VARCHAR(500) NOT NULL,
    dependencies VARCHAR(500) NOT NULL,
    UNIQUE (teaching_plan_id, position),
    UNIQUE (teaching_plan_id, section_type)
);
