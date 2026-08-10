UPDATE teaching_plan AS plan
SET game_title = game.name
FROM document_version AS version
JOIN rule_document AS document ON document.id = version.document_id
JOIN game_edition AS edition ON edition.id = document.game_edition_id
JOIN game ON game.id = edition.game_id
WHERE plan.document_version_id = version.id
  AND plan.game_title IS DISTINCT FROM game.name;
