ALTER TABLE assistant_run
    DROP CONSTRAINT ck_assistant_run_mode;

ALTER TABLE assistant_run
    ADD CONSTRAINT ck_assistant_run_mode
        CHECK (mode IN ('TEACHING_PREPARATION', 'TEACHING', 'VISUAL_ENRICHMENT', 'QUESTION_ANSWER'));
