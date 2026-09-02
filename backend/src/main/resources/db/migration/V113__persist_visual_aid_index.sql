CREATE TABLE visual_aid_index (
    document_version_id UUID PRIMARY KEY REFERENCES document_version(id) ON DELETE CASCADE,
    source VARCHAR(120) NOT NULL,
    page_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_visual_aid_index_page_count CHECK (page_count > 0)
);

CREATE TABLE visual_aid_region (
    id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES visual_aid_index(document_version_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    page_number INTEGER NOT NULL,
    kind VARCHAR(40) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_visual_aid_region_ordinal UNIQUE (document_version_id, ordinal),
    CONSTRAINT ck_visual_aid_region_page CHECK (page_number > 0),
    CONSTRAINT ck_visual_aid_region_bounds CHECK (
        ordinal >= 0
        AND x >= 0 AND y >= 0
        AND width >= 20 AND height >= 20
        AND x + width <= 1000
        AND y + height <= 1000
        AND NOT (x = 0 AND y = 0 AND width = 1000 AND height = 1000)
    )
);

CREATE INDEX ix_visual_aid_region_version_page
    ON visual_aid_region (document_version_id, page_number, ordinal);

COMMENT ON TABLE visual_aid_region IS
    'Provider-neutral page geometry owned by the optional visual-aid module; prose never determines coordinates.';
