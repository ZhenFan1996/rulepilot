ALTER TABLE illustrated_lesson_step
    ADD COLUMN visual_foci_json TEXT NOT NULL DEFAULT '[]';

COMMENT ON COLUMN illustrated_lesson_step.visual_foci_json IS
    'Ordered, source-typed visual foci for the lesson step; legacy visual_* columns retain the first focus.';
