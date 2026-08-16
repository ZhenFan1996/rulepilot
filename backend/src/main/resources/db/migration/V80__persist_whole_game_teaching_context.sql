ALTER TABLE teaching_plan
    ADD COLUMN whole_game_context JSONB NOT NULL
    DEFAULT '{"summary":"Legacy teaching plan without a source-bound whole-game context.","concepts":[],"topicDependencies":[],"evidenceBound":false}'::jsonb;

ALTER TABLE teaching_plan
    ALTER COLUMN whole_game_context DROP DEFAULT;
