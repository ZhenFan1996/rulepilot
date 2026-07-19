ALTER TABLE illustrated_lesson_section
    ADD COLUMN visual_source_pages VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN visual_source_chunk_ids VARCHAR(500) NOT NULL DEFAULT '';
