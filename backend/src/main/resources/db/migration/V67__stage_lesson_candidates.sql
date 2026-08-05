ALTER TABLE illustrated_lesson
    ADD COLUMN publication_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE illustrated_lesson
    ADD CONSTRAINT ck_illustrated_lesson_publication_state
        CHECK (publication_state IN ('ACTIVE', 'CANDIDATE', 'ARCHIVED'));

CREATE INDEX ix_illustrated_lesson_plan_publication_created
    ON illustrated_lesson (teaching_plan_id, publication_state, created_at DESC);
