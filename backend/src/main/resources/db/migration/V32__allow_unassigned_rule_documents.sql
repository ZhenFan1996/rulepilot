ALTER TABLE rule_document
    ALTER COLUMN game_edition_id DROP NOT NULL;

DROP INDEX uk_rule_document_scope;

CREATE UNIQUE INDEX uk_rule_document_assigned_scope
    ON rule_document (game_edition_id, lower(title), source_type)
    WHERE game_edition_id IS NOT NULL;

CREATE UNIQUE INDEX uk_rule_document_unassigned_owner_scope
    ON rule_document (created_by, lower(title), source_type)
    WHERE game_edition_id IS NULL;
