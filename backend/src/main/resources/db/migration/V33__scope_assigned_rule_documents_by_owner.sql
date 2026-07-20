DROP INDEX uk_rule_document_assigned_scope;

CREATE UNIQUE INDEX uk_rule_document_assigned_owner_scope
    ON rule_document (game_edition_id, created_by, lower(title), source_type)
    WHERE game_edition_id IS NOT NULL;
