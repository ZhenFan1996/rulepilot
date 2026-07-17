CREATE TABLE illustrated_lesson (
    id UUID PRIMARY KEY,
    teaching_plan_id UUID NOT NULL REFERENCES teaching_plan(id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_illustrated_lesson_plan_created
    ON illustrated_lesson (teaching_plan_id, created_at DESC);

CREATE TABLE illustrated_lesson_section (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES illustrated_lesson(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position > 0),
    section_type VARCHAR(50) NOT NULL,
    title VARCHAR(160) NOT NULL,
    required BOOLEAN NOT NULL,
    evidence_status VARCHAR(40) NOT NULL,
    visual_kind VARCHAR(40) NOT NULL,
    visual_caption VARCHAR(240) NOT NULL,
    UNIQUE (lesson_id, position),
    UNIQUE (lesson_id, section_type)
);

CREATE TABLE illustrated_lesson_step (
    id UUID PRIMARY KEY,
    lesson_section_id UUID NOT NULL REFERENCES illustrated_lesson_section(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position > 0),
    step_text TEXT NOT NULL,
    source_pages VARCHAR(500) NOT NULL,
    UNIQUE (lesson_section_id, position)
);
