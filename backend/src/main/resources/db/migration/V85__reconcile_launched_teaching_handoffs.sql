ALTER TABLE official_rulebook_import_job
    ADD COLUMN teaching_handoff_reconciled_at TIMESTAMPTZ NULL,
    ADD COLUMN teaching_automatic_recovery_count SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_official_rulebook_teaching_automatic_recovery_count
        CHECK (teaching_automatic_recovery_count BETWEEN 0 AND 1);

CREATE INDEX ix_official_rulebook_import_teaching_unreconciled
    ON official_rulebook_import_job (teaching_handoff_updated_at)
    WHERE teaching_handoff_state = 'LAUNCHED'
      AND teaching_handoff_reconciled_at IS NULL;
