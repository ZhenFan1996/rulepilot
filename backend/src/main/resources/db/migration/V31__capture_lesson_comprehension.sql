CREATE TABLE lesson_comprehension_result (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES illustrated_lesson(id) ON DELETE CASCADE,
    task_type VARCHAR(40) NOT NULL CHECK (task_type IN (
        'PREPARE_TABLE', 'PLAY_A_ROUND', 'FINISH_GAME', 'SCORE_GAME'
    )),
    result VARCHAR(20) NOT NULL CHECK (result IN ('CAN_DO', 'NEEDS_HELP')),
    created_by VARCHAR(120) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (lesson_id, created_by, task_type)
);

CREATE INDEX ix_lesson_comprehension_owner
    ON lesson_comprehension_result (lesson_id, created_by);
