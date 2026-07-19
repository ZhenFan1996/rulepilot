ALTER TABLE illustrated_lesson_step
    ADD COLUMN step_heading VARCHAR(80) NOT NULL DEFAULT '照着做',
    ADD COLUMN teaching_move VARCHAR(30) NOT NULL DEFAULT 'DO';
