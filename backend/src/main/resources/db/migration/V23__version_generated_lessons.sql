ALTER TABLE illustrated_lesson
    ADD COLUMN generator_version VARCHAR(80) NOT NULL DEFAULT 'legacy';

ALTER TABLE illustrated_lesson
    ALTER COLUMN generator_version DROP DEFAULT;
