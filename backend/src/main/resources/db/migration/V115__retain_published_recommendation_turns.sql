-- Move the last retained publication into a turn-bound history. Earlier publications were
-- never stored and cannot be reconstructed from model prose. This retires the singleton.
WITH publications AS (
    SELECT id, state_json,
           COALESCE(NULLIF(state_json -> 'latestPublishedTurn', 'null'::jsonb),
               CASE WHEN last_response_json ->> 'outcome' <> 'UNAVAILABLE'
                         AND last_client_turn_id IS NOT NULL AND last_response_locale IS NOT NULL
                    THEN jsonb_build_object('clientTurnId', last_client_turn_id,
                                            'responseLocale', last_response_locale,
                                            'response', last_response_json) END) AS publication
    FROM recommendation_conversation
    WHERE NOT state_json ? 'publishedTurns'
), positions AS (
    SELECT *, CASE WHEN COALESCE(publication #>> '{response,assistantMessage}', '') <> ''
                  THEN (SELECT MAX(ordinality)::integer - 1
                        FROM jsonb_array_elements(state_json -> 'transcript') WITH ORDINALITY AS item(message, ordinality)
                        WHERE message ->> 'role' = 'assistant') END AS assistant_index
    FROM publications
)
UPDATE recommendation_conversation conversation
SET state_json = (positions.state_json - 'latestPublishedTurn') || jsonb_build_object(
    'publishedTurns', CASE WHEN publication IS NULL THEN '[]'::jsonb
                          ELSE jsonb_build_array(publication || jsonb_build_object('transcriptIndex',
                              COALESCE(assistant_index, jsonb_array_length(positions.state_json -> 'transcript')))) END,
    'transcript', CASE WHEN publication IS NOT NULL AND assistant_index IS NULL
                      THEN (positions.state_json -> 'transcript') || jsonb_build_array(jsonb_build_object(
                          'role', 'assistant', 'text', COALESCE(publication #>> '{response,assistantMessage}', '')))
                      ELSE positions.state_json -> 'transcript' END)
FROM positions WHERE conversation.id = positions.id;
