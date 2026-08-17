ALTER TABLE teaching_plan
    ALTER COLUMN learning_goal TYPE TEXT;

ALTER TABLE official_rulebook_import_job
    ALTER COLUMN teaching_learning_goal TYPE TEXT;

ALTER TABLE uploaded_rulebook_teaching_handoff
    ALTER COLUMN learning_goal TYPE TEXT;
