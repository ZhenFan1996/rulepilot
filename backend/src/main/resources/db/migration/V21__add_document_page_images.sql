ALTER TABLE document_page
    ADD COLUMN image_object_key VARCHAR(500),
    ADD COLUMN image_width INTEGER,
    ADD COLUMN image_height INTEGER;

ALTER TABLE document_page
    ADD CONSTRAINT ck_document_page_image_metadata
    CHECK (
        (image_object_key IS NULL AND image_width IS NULL AND image_height IS NULL)
        OR (image_object_key IS NOT NULL AND image_width > 0 AND image_height > 0)
    );
