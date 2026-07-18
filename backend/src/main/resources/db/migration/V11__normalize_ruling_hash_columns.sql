ALTER TABLE confirmed_ruling
    ALTER COLUMN expansion_set_hash TYPE VARCHAR(64),
    ALTER COLUMN normalized_question_hash TYPE VARCHAR(64);
