ALTER TABLE illustrated_lesson_step
    ADD COLUMN reading_blocks_json text NOT NULL DEFAULT '[]';

COMMENT ON COLUMN illustrated_lesson_step.reading_blocks_json IS
    'Agent-authored paragraph and figure order. Empty only for retained pre-flow lessons; retire the legacy reader once those lessons and offline snapshots are replaced.';
