ALTER TABLE lesson_comprehension_result
    DROP CONSTRAINT lesson_comprehension_result_task_type_check,
    DROP CONSTRAINT lesson_comprehension_result_result_check;

ALTER TABLE lesson_comprehension_result
    ADD CONSTRAINT lesson_comprehension_result_task_type_check CHECK (task_type IN (
        'PREPARE_TABLE', 'PLAY_A_ROUND', 'FINISH_GAME', 'SCORE_GAME',
        'VERIFY_VISUAL_AID', 'IDENTIFY_COMPONENTS', 'COMPLETE_VISUAL_SETUP'
    )),
    ADD CONSTRAINT lesson_comprehension_result_result_check CHECK (result IN (
        'NOT_TRIED', 'CAN_DO', 'NEEDS_HELP'
    ));
