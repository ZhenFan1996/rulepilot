CREATE TABLE lesson_localization (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES illustrated_lesson(id) ON DELETE CASCADE,
    locale VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    translated_sections TEXT,
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_lesson_localization_lesson_locale UNIQUE (lesson_id, locale)
);

CREATE INDEX ix_lesson_localization_lesson_locale
    ON lesson_localization (lesson_id, locale);
