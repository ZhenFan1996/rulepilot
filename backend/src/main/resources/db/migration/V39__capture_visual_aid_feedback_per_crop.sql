CREATE TABLE lesson_visual_aid_feedback (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES illustrated_lesson(id) ON DELETE CASCADE,
    visual_aid_key VARCHAR(40) NOT NULL CHECK (visual_aid_key ~ '^s[1-9][0-9]*-v[1-9][0-9]*$'),
    result VARCHAR(20) NOT NULL CHECK (result IN ('HELPFUL', 'NOT_HELPFUL')),
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (lesson_id, created_by, visual_aid_key)
);

CREATE INDEX ix_lesson_visual_aid_feedback_owner
    ON lesson_visual_aid_feedback (lesson_id, created_by);
