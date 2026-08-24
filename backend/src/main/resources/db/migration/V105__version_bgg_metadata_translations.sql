ALTER TABLE bgg_metadata_translation
    ADD COLUMN contract_version INTEGER NOT NULL DEFAULT 4;

ALTER TABLE bgg_metadata_translation
    ALTER COLUMN contract_version DROP DEFAULT;

ALTER TABLE bgg_metadata_translation
    DROP CONSTRAINT bgg_metadata_translation_pkey;

ALTER TABLE bgg_metadata_translation
    DROP CONSTRAINT bgg_metadata_translation_locale_check;

ALTER TABLE bgg_metadata_translation
    ADD CONSTRAINT ck_bgg_metadata_translation_locale
        CHECK (locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    ADD CONSTRAINT ck_bgg_metadata_translation_contract_version
        CHECK (contract_version > 0),
    ADD PRIMARY KEY (bgg_id, locale, contract_version, source_sha256);
