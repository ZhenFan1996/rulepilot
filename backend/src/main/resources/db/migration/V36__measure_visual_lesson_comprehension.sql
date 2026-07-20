ALTER TABLE lesson_comprehension_result
    DROP CONSTRAINT lesson_comprehension_result_task_type_check;

ALTER TABLE lesson_comprehension_result
    ADD CONSTRAINT lesson_comprehension_result_task_type_check CHECK (task_type IN (
        'PREPARE_TABLE', 'PLAY_A_ROUND', 'FINISH_GAME', 'SCORE_GAME',
        'IDENTIFY_COMPONENTS', 'COMPLETE_VISUAL_SETUP'
    )),
    ADD COLUMN visual_aid_result VARCHAR(20),
    ADD CONSTRAINT lesson_comprehension_result_visual_aid_check CHECK (
        visual_aid_result IS NULL OR visual_aid_result IN ('HELPFUL', 'NOT_HELPFUL')
    );
