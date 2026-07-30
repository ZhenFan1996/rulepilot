ALTER TABLE illustrated_lesson_step
    ADD COLUMN visual_description VARCHAR(240) NOT NULL DEFAULT '';

COMMENT ON COLUMN illustrated_lesson_step.visual_description IS
    'Literal, model-observed content inside a verified crop; never standalone rule evidence.';
