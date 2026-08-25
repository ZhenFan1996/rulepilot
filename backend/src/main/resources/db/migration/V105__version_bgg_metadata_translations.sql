CREATE TABLE bgg_metadata_translation_versioned (
    bgg_id INTEGER NOT NULL CHECK (bgg_id > 0),
    locale VARCHAR(16) NOT NULL
        CHECK (locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    contract_version INTEGER NOT NULL CHECK (contract_version > 0),
    source_sha256 CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL,
    payload_bytes INTEGER NOT NULL CHECK (payload_bytes > 0 AND payload_bytes <= 131072),
    translated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bgg_id, locale, contract_version, source_sha256)
);

CREATE INDEX ix_bgg_metadata_translation_versioned_translated_at
    ON bgg_metadata_translation_versioned (translated_at);

CREATE FUNCTION replicate_legacy_bgg_metadata_translation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO bgg_metadata_translation_versioned (
        bgg_id, locale, contract_version, source_sha256, payload, payload_bytes, translated_at)
    VALUES (
        NEW.bgg_id, NEW.locale, 4, NEW.source_sha256, NEW.payload, NEW.payload_bytes, NEW.translated_at)
    ON CONFLICT (bgg_id, locale, contract_version, source_sha256) DO UPDATE SET
        payload = EXCLUDED.payload,
        payload_bytes = EXCLUDED.payload_bytes,
        translated_at = GREATEST(
            bgg_metadata_translation_versioned.translated_at,
            EXCLUDED.translated_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_bgg_metadata_translation_legacy_replication
AFTER INSERT OR UPDATE ON bgg_metadata_translation
FOR EACH ROW
EXECUTE FUNCTION replicate_legacy_bgg_metadata_translation();

INSERT INTO bgg_metadata_translation_versioned (
    bgg_id, locale, contract_version, source_sha256, payload, payload_bytes, translated_at)
SELECT bgg_id, locale, 4, source_sha256, payload, payload_bytes, translated_at
FROM bgg_metadata_translation;
