ALTER TABLE illustrated_lesson_step
    ADD COLUMN visual_page INTEGER,
    ADD COLUMN visual_label VARCHAR(80),
    ADD COLUMN visual_x INTEGER,
    ADD COLUMN visual_y INTEGER,
    ADD COLUMN visual_width INTEGER,
    ADD COLUMN visual_height INTEGER;

ALTER TABLE illustrated_lesson_step
    ADD CONSTRAINT ck_lesson_step_visual_focus
    CHECK (
        (visual_page IS NULL
            AND visual_label IS NULL
            AND visual_x IS NULL
            AND visual_y IS NULL
            AND visual_width IS NULL
            AND visual_height IS NULL)
        OR
        (visual_page > 0
            AND visual_label IS NOT NULL
            AND length(trim(visual_label)) > 0
            AND visual_x >= 0
            AND visual_y >= 0
            AND visual_width >= 20
            AND visual_height >= 20
            AND visual_x + visual_width <= 1000
            AND visual_y + visual_height <= 1000)
    );
