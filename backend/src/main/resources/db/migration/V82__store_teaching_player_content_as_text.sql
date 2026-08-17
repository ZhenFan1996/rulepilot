ALTER TABLE teaching_plan
    ALTER COLUMN game_title TYPE TEXT;

ALTER TABLE teaching_plan_section
    ALTER COLUMN topic_key TYPE TEXT,
    ALTER COLUMN title TYPE TEXT,
    ALTER COLUMN coverage_tags TYPE TEXT,
    ALTER COLUMN source_page_numbers TYPE TEXT;

ALTER TABLE illustrated_lesson_section
    ALTER COLUMN topic_key TYPE TEXT,
    ALTER COLUMN coverage_tags TYPE TEXT,
    ALTER COLUMN title TYPE TEXT,
    ALTER COLUMN visual_caption TYPE TEXT,
    ALTER COLUMN visual_source_pages TYPE TEXT,
    ALTER COLUMN visual_source_chunk_ids TYPE TEXT;

ALTER TABLE illustrated_lesson_step
    ALTER COLUMN step_heading TYPE TEXT,
    ALTER COLUMN source_pages TYPE TEXT,
    ALTER COLUMN source_chunk_ids TYPE TEXT,
    ALTER COLUMN visual_label TYPE TEXT,
    ALTER COLUMN visual_description TYPE TEXT;
