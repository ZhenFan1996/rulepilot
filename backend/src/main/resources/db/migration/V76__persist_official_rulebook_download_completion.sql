ALTER TABLE official_rulebook_import_job
    ADD COLUMN download_completed_at TIMESTAMPTZ NULL;

ALTER TABLE official_rulebook_import_job
    ADD CONSTRAINT ck_official_rulebook_download_completed_at CHECK (
        download_completed_at IS NULL OR download_completed_at >= created_at
    );

CREATE INDEX idx_official_rulebook_ready_handoff_document
    ON official_rulebook_import_job (document_version_id, created_at)
    WHERE stage = 'COMPLETED'
      AND teaching_handoff_state = 'WAITING_FOR_DOCUMENT';

CREATE INDEX idx_uploaded_rulebook_ready_handoff_document
    ON uploaded_rulebook_teaching_handoff (document_version_id, created_at)
    WHERE state = 'WAITING_FOR_DOCUMENT';
