ALTER TABLE teaching_plan
    ADD COLUMN game_title VARCHAR(200),
    ADD COLUMN premise TEXT;

UPDATE teaching_plan
SET game_title = 'Imported rulebook',
    premise = 'Legacy teaching plan generated before adaptive outlines.';

ALTER TABLE teaching_plan
    ALTER COLUMN game_title SET NOT NULL,
    ALTER COLUMN premise SET NOT NULL;

ALTER TABLE teaching_plan_section DROP CONSTRAINT teaching_plan_section_teaching_plan_id_section_type_key;
ALTER TABLE teaching_plan_section RENAME COLUMN section_type TO topic_key;
ALTER TABLE teaching_plan_section
    ADD COLUMN title VARCHAR(160),
    ADD COLUMN objective TEXT,
    ADD COLUMN retrieval_queries TEXT,
    ADD COLUMN coverage_tags VARCHAR(500);

UPDATE teaching_plan_section
SET title = replace(initcap(replace(lower(topic_key), '_', ' ')), 'Tie Breakers', 'Tie breakers'),
    objective = 'Legacy topic imported for adaptive teaching.',
    retrieval_queries = replace(lower(topic_key), '_', ' '),
    coverage_tags = lower(topic_key);

ALTER TABLE teaching_plan_section
    ALTER COLUMN title SET NOT NULL,
    ALTER COLUMN objective SET NOT NULL,
    ALTER COLUMN retrieval_queries SET NOT NULL,
    ALTER COLUMN coverage_tags SET NOT NULL;

ALTER TABLE teaching_plan_section
    DROP COLUMN evidence_available,
    DROP COLUMN source_pages,
    DROP COLUMN dependencies;

ALTER TABLE illustrated_lesson_section DROP CONSTRAINT illustrated_lesson_section_lesson_id_section_type_key;
ALTER TABLE illustrated_lesson_section RENAME COLUMN section_type TO topic_key;
ALTER TABLE illustrated_lesson_section ADD COLUMN coverage_tags VARCHAR(500) NOT NULL DEFAULT '';
