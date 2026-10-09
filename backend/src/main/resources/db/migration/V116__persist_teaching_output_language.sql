ALTER TABLE teaching_plan ADD COLUMN output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'
    CHECK (output_language IN ('ZH_CN', 'EN'));
ALTER TABLE uploaded_rulebook_teaching_handoff ADD COLUMN output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'
    CHECK (output_language IN ('ZH_CN', 'EN'));
ALTER TABLE official_rulebook_import_job ADD COLUMN teaching_output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'
    CHECK (teaching_output_language IN ('ZH_CN', 'EN'));
ALTER TABLE assistant_run ADD COLUMN output_language VARCHAR(16) NOT NULL DEFAULT 'ZH_CN'
    CHECK (output_language IN ('ZH_CN', 'EN'));
